package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** P3：产值唯一性与锁、凭证附件绑定/解绑、第三方报告单文件、企业档案。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutputEvidenceTest extends ApiTestSupport {

    private static final String PERIOD = "2026-12";
    private static Long productionId;
    private static Long evidenceId;
    private static Long thirdId;

    @Test
    @Order(1)
    void productionValueCrudAndUnique() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/production-values")
                .param("from", "2026-11").param("to", "2026-11"), filler, null);
        assertThat(list.http()).isEqualTo(200);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(2);

        Resp created = call(post("/api/production-values"), filler, Map.of(
                "period", PERIOD, "productId", 1, "value", 120.5, "unit", "万元",
                "qty", 20, "qtyUnit", "吨"));
        assertThat(created.http()).isEqualTo(200);
        productionId = created.body().path("id").asLong();
        assertThat(created.body().path("productName").asText()).isEqualTo("精密轴承座 A 型");
        assertThat(created.body().path("version").asInt()).isZero();

        Resp dup = call(post("/api/production-values"), filler, Map.of(
                "period", PERIOD, "productId", 1, "value", 1, "unit", "万元",
                "qty", 1, "qtyUnit", "吨"));
        assertThat(dup.http()).as("同月同产品只能一条").isEqualTo(409);

        Resp patched = call(put("/api/production-values/" + productionId), filler, Map.of(
                "period", PERIOD, "productId", 1, "value", 130, "unit", "万元",
                "qty", 21, "qtyUnit", "吨", "version", 0));
        assertThat(patched.http()).isEqualTo(200);
        assertThat(patched.body().path("version").asInt()).isEqualTo(1);

        Resp stale = call(put("/api/production-values/" + productionId), filler, Map.of(
                "period", PERIOD, "productId", 1, "value", 140, "unit", "万元",
                "qty", 22, "qtyUnit", "吨", "version", 0));
        assertThat(stale.http()).isEqualTo(409);
        assertThat(stale.body().path("detail").path("currentVersion").asInt()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void productionValueValidatesProductAndRole() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String viewer = login("viewer", "Test@2026!Pass");

        Resp unknown = call(post("/api/production-values"), filler, Map.of(
                "period", PERIOD, "productId", 99999, "value", 1, "unit", "万元",
                "qty", 1, "qtyUnit", "吨"));
        assertThat(unknown.http()).isEqualTo(400);
        assertThat(unknown.body().path("message").asText()).contains("产品不存在");

        String limited = limitedUser(java.util.List.of("home"));
        Resp forbidden = call(post("/api/production-values"), limited, Map.of(
                "period", PERIOD, "productId", 2, "value", 1, "unit", "万元",
                "qty", 1, "qtyUnit", "吨"));
        assertThat(forbidden.http()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("PAGE_FORBIDDEN");
    }

    @Test
    @Order(3)
    void totalValueCrud() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp created = call(post("/api/total-values"), filler, Map.of(
                "period", PERIOD, "name", "全厂生产总值", "qty", 100, "qtyUnit", "吨",
                "value", 88, "unit", "万元"));
        assertThat(created.http()).isEqualTo(200);
        long id = created.body().path("id").asLong();

        Resp dup = call(post("/api/total-values"), filler, Map.of(
                "period", PERIOD, "name", "全厂生产总值", "qty", 1, "qtyUnit", "吨",
                "value", 1, "unit", "万元"));
        assertThat(dup.http()).isEqualTo(409);

        Resp patched = call(put("/api/total-values/" + id), filler, Map.of(
                "period", PERIOD, "name", "全厂生产总值", "qty", 110, "qtyUnit", "吨",
                "value", 96, "unit", "万元", "version", 0));
        assertThat(patched.http()).isEqualTo(200);
        assertThat(patched.body().path("outputValue").decimalValue()).isEqualByComparingTo("96");
    }

    @Test
    @Order(4)
    void evidenceBindsAndUnbindsFiles() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        long fileId = upload(filler, "电费发票-测试.pdf");

        Resp created = call(post("/api/evidences"), filler, Map.of(
                "name", "测试电费发票", "code", "IT-EV-001", "description", "附件绑定验证",
                "validFrom", "2026-12-01", "validTo", "2026-12-31", "atDate", "2026-12-05",
                "fileIds", List.of(fileId)));
        assertThat(created.http()).isEqualTo(200);
        evidenceId = created.body().path("id").asLong();
        assertThat(created.body().path("files")).hasSize(1);
        assertThat(created.body().path("files").get(0).path("id").asLong()).isEqualTo(fileId);

        Resp dupCode = call(post("/api/evidences"), filler, Map.of(
                "name", "重复编号", "code", "IT-EV-001",
                "validFrom", "2026-12-01", "atDate", "2026-12-05"));
        assertThat(dupCode.http()).as("凭证编号同企业内唯一").isEqualTo(409);

        Resp badRange = call(post("/api/evidences"), filler, Map.of(
                "name", "有效期倒挂", "validFrom", "2026-12-31", "validTo", "2026-12-01",
                "atDate", "2026-12-05"));
        assertThat(badRange.http()).isEqualTo(400);

        Resp cleared = call(put("/api/evidences/" + evidenceId), filler, Map.of(
                "name", "测试电费发票", "code", "IT-EV-001",
                "validFrom", "2026-12-01", "validTo", "2026-12-31", "atDate", "2026-12-05",
                "fileIds", List.of(), "version", 0));
        assertThat(cleared.http()).isEqualTo(200);
        assertThat(cleared.body().path("files")).isEmpty();

        Resp rebound = call(put("/api/evidences/" + evidenceId), filler, Map.of(
                "name", "测试电费发票", "code", "IT-EV-001",
                "validFrom", "2026-12-01", "validTo", "2026-12-31", "atDate", "2026-12-05",
                "fileIds", List.of(fileId), "version", 1));
        assertThat(rebound.body().path("files")).hasSize(1);
    }

    @Test
    @Order(5)
    void thirdPartyReportSingleFile() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        long fileId = upload(filler, "核查报告-测试.pdf");

        Resp created = call(post("/api/third-party-reports"), filler, Map.of(
                "name", "测试核查报告", "description", "单文件关联",
                "validFrom", "2026-01-01", "validTo", "2027-12-31", "atDate", "2026-12-06",
                "fileId", fileId));
        assertThat(created.http()).isEqualTo(200);
        thirdId = created.body().path("id").asLong();
        assertThat(created.body().path("file").path("id").asLong()).isEqualTo(fileId);

        Resp list = call(get("/api/third-party-reports").param("keyword", "测试核查"), filler, null);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(1);

        Resp cleared = call(put("/api/third-party-reports/" + thirdId), filler, Map.of(
                "name", "测试核查报告", "validFrom", "2026-01-01", "validTo", "2027-12-31",
                "atDate", "2026-12-06", "version", 0));
        assertThat(cleared.http()).isEqualTo(200);
        assertThat(cleared.body().path("file").isMissingNode()).as("解绑后不再返回文件").isTrue();

        Resp missingFile = call(post("/api/third-party-reports"), filler, Map.of(
                "name", "文件不存在", "validFrom", "2026-01-01", "atDate", "2026-12-06",
                "fileId", 999999));
        assertThat(missingFile.http()).isEqualTo(404);
    }

    @Test
    @Order(6)
    void orgProfileReadOnlyForFiller() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String admin = login("admin", "Admin@2026!Pass");

        Resp read = call(get("/api/org"), filler, null);
        assertThat(read.http()).isEqualTo(200);
        assertThat(read.body().path("shortName").asText()).isEqualTo("华东精密");
        int version = read.body().path("version").asInt();

        Resp denied = call(put("/api/org"), filler, Map.of(
                "name", "华东精密制造有限公司", "code", "HDJM", "version", version));
        assertThat(denied.http()).isEqualTo(403);

        Resp updated = call(put("/api/org"), admin, Map.of(
                "name", "华东精密制造有限公司", "shortName", "华东精密", "code", "HDJM",
                "phone", "0512-6688 999", "version", version));
        assertThat(updated.http()).isEqualTo(200);
        assertThat(updated.body().path("phone").asText()).isEqualTo("0512-6688 999");

        Resp stale = call(put("/api/org"), admin, Map.of(
                "name", "华东精密制造有限公司", "code", "HDJM", "version", version));
        assertThat(stale.http()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void adminSoftDeletesEvidenceAndProduction() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        assertThat(call(delete("/api/evidences/" + evidenceId), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/evidences/" + evidenceId), admin, null).http()).isEqualTo(404);

        assertThat(call(delete("/api/production-values/" + productionId), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/production-values/" + productionId), admin, null).http()).isEqualTo(404);

        long fileId = upload(admin, "待删报告.pdf");
        Resp third = call(post("/api/third-party-reports"), admin, Map.of(
                "name", "待删第三方报告", "validFrom", "2026-01-01", "atDate", "2026-12-07",
                "fileId", fileId));
        long id = third.body().path("id").asLong();
        assertThat(call(delete("/api/third-party-reports/" + id), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/third-party-reports/" + id), admin, null).http()).isEqualTo(404);
    }

    private long upload(String token, String name) throws Exception {
        var res = mvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", name, "application/pdf",
                                "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode node = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long id = node.path("body").path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }
}
