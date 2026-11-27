package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceQuotaJobTest extends ApiTestSupport {

    private static Long deviceId;
    private static Long quotaId;

    @Test
    @Order(1)
    void deviceCrudAndReadingGuard() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        String filler = login("filler", "Test@2026!Pass");

        Resp created = call(post("/api/devices"), filler, Map.of(
                "name", "测试空压机", "code", "EQ-T-001", "energyType", "外购电力",
                "status", "online", "deviceType", "电表", "level", "主要用能设备"));
        assertThat(created.http()).isEqualTo(200);
        deviceId = created.body().path("id").asLong();
        assertThat(created.body().path("version").asInt()).isZero();

        Resp dup = call(post("/api/devices"), filler, Map.of(
                "name", "重复编码", "code", "EQ-T-001", "energyType", "外购电力"));
        assertThat(dup.http()).isEqualTo(409);

        Resp patched = call(put("/api/devices/" + deviceId), filler, Map.of(
                "name", "测试空压机-改", "code", "EQ-T-001", "energyType", "外购电力",
                "status", "online", "version", 0));
        assertThat(patched.http()).isEqualTo(200);
        assertThat(patched.body().path("name").asText()).isEqualTo("测试空压机-改");

        Resp stale = call(put("/api/devices/" + deviceId), filler, Map.of(
                "name", "旧版本", "code", "EQ-T-001", "energyType", "外购电力", "version", 0));
        assertThat(stale.http()).isEqualTo(409);

        Resp disabled = call(post("/api/devices/" + deviceId + "/disable"), filler, null);
        assertThat(disabled.http()).isEqualTo(200);
        Resp list = call(get("/api/devices?keyword=EQ-T-001"), filler, null);
        assertThat(list.body()).isEmpty();
        Resp withDisabled = call(get("/api/devices?keyword=EQ-T-001&includeDisabled=true"), filler, null);
        assertThat(withDisabled.body().size()).isEqualTo(1);

        assertThat(call(post("/api/devices/" + deviceId + "/enable"), filler, null).http()).isEqualTo(200);

        // 有读数后拒绝删除
        Resp reading = call(post("/api/device-readings"), filler, Map.of(
                "deviceId", deviceId, "ts", "2026-11-20T08:00:00", "qty", 10,
                "unit", "mWh", "rate", 1, "source", "手工补录"));
        assertThat(reading.http()).isEqualTo(200);
        Resp blocked = call(delete("/api/devices/" + deviceId), admin, null);
        assertThat(blocked.http()).isEqualTo(409);
        assertThat(blocked.body().path("detail").path("readings").asInt()).isGreaterThan(0);

        // 无读数设备可删除
        Resp tmp = call(post("/api/devices"), admin, Map.of(
                "name", "临时设备", "code", "EQ-T-002", "energyType", "外购电力"));
        assertThat(call(delete("/api/devices/" + tmp.body().path("id").asLong()), admin, null).http())
                .isEqualTo(200);
    }

    @Test
    @Order(2)
    void quotaYearCrud() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        String filler = login("filler", "Test@2026!Pass");

        Resp created = call(post("/api/quota-years"), filler, Map.of(
                "year", 2031, "total", 12000, "next", 11000, "note", "测试年度"));
        assertThat(created.http()).isEqualTo(200);
        quotaId = created.body().path("id").asLong();

        Resp dup = call(post("/api/quota-years"), filler, Map.of("year", 2031, "total", 1));
        assertThat(dup.http()).isEqualTo(409);

        Resp patched = call(put("/api/quota-years/" + quotaId), filler, Map.of(
                "year", 2031, "total", 13000, "next", 12000, "version", 0));
        assertThat(patched.http()).isEqualTo(200);
        assertThat(patched.body().path("totalQty").asInt()).isEqualTo(13000);

        assertThat(call(put("/api/quota-years/" + quotaId), filler, Map.of(
                "year", 2031, "total", 1, "version", 0)).http()).isEqualTo(409);

        Resp list = call(get("/api/quota-years?year=2031"), filler, null);
        assertThat(list.body().size()).isEqualTo(1);

        assertThat(call(delete("/api/quota-years/" + quotaId), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/quota-years?year=2031"), filler, null).body()).isEmpty();
    }

    @Test
    @Order(3)
    void csvImportProducts() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String csv = "编码,名称,规格型号,产品类型,产品分类,单位\n"
                + "T-IMP-1,导入测试产品,TS-1,一般产品,工业产品,吨\n";
        MockMultipartFile file = new MockMultipartFile("file", "products.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        JsonNode body = multipartJson("/api/import?resource=products", filler, file);
        assertThat(body.path("success").asInt()).isEqualTo(1);
        assertThat(body.path("failed").asInt()).isZero();

        JsonNode again = multipartJson("/api/import?resource=products", filler, file);
        assertThat(again.path("failed").asInt()).isEqualTo(1);
        assertThat(again.path("errors").get(0).path("reason").asText()).contains("编码已存在");

        String tpl = mvc.perform(get("/api/import/template?resource=products")
                        .header("Authorization", "Bearer " + filler))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(tpl).contains("编码,名称");
    }

    @Test
    @Order(4)
    void jobsRunManually() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");

        Resp list = call(get("/api/jobs"), admin, null);
        assertThat(list.http()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(3);

        for (String job : new String[]{"scan-alerts", "quota-reminder", "monthly-reports"}) {
            Resp r = call(post("/api/jobs/" + job + "/run"), admin, null);
            assertThat(r.http()).as("job " + job).isEqualTo(200);
            assertThat(r.body().path("job").asText()).isEqualTo(job);
            assertThat(r.body().path("created").asInt()).isGreaterThanOrEqualTo(0);
        }

        Resp unknown = call(post("/api/jobs/not-exist/run"), admin, null);
        assertThat(unknown.http()).isEqualTo(400);

        String filler = login("filler", "Test@2026!Pass");
        assertThat(call(post("/api/jobs/scan-alerts/run"), filler, null).http()).isEqualTo(403);
    }

    @Test
    @Order(5)
    void healthIsPublic() throws Exception {
        Resp r = call(get("/api/health"), null, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("db").asText()).isEqualTo("UP");
    }

    private JsonNode multipartJson(String path, String token, MockMultipartFile file) throws Exception {
        String text = mvc.perform(multipart(path).file(file)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(text).path("body");
    }
}
