package com.carbonbal;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** CSV 导出与报告 ZIP 打包。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExportTest extends ApiTestSupport {

    @Test
    @Order(1)
    void exportsProductionValuesAsCsv() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        byte[] csv = mvc.perform(get("/api/export")
                        .param("resource", "production-values")
                        .param("from", "2026-09").param("to", "2026-11")
                        .header("Authorization", "Bearer " + filler))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv.length).isGreaterThan(20);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF月份,产品,产值");
        assertThat(text).contains("精密轴承座 A 型").contains("2026-11");
    }

    @Test
    @Order(2)
    void exportsReadingsWithDeviceColumns() throws Exception {
        String viewer = login("viewer", "Test@2026!Pass");
        byte[] csv = mvc.perform(get("/api/export")
                        .param("resource", "readings")
                        .param("from", "2026-11-01").param("to", "2026-11-30")
                        .header("Authorization", "Bearer " + viewer))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("设备编码").contains("EQ-A101").contains("自动采集");
        assertThat(text.lines().count()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @Order(3)
    void rejectsUnknownResourceAndRequiresLogin() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp bad = call(get("/api/export").param("resource", "nope"), filler, null);
        assertThat(bad.http()).isEqualTo(400);

        int status = mvc.perform(get("/api/export").param("resource", "products"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    @Order(4)
    void bundlesReportsAsZip() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp calc = call(post("/api/accounting-periods/2026-11/calculate"), filler, null);
        assertThat(calc.http()).isEqualTo(200);
        Resp pv = call(get("/api/accounting-periods/2026-11"), filler, null);
        if (!"submitted".equals(pv.body().path("status").asText())) {
            assertThat(call(post("/api/accounting-periods/2026-11/submit"), filler,
                    Map.of("version", pv.body().path("version").asInt())).http()).isEqualTo(200);
        }
        assertThat(call(post("/api/reports"), filler, Map.of("type", "carbon", "period", "2026-11")).http())
                .isEqualTo(200);
        assertThat(call(post("/api/reports"), filler, Map.of("type", "energy", "period", "2026-11")).http())
                .isEqualTo(200);

        var res = mvc.perform(get("/api/reports/bundle")
                        .param("period", "2026-11")
                        .header("Authorization", "Bearer " + filler))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        byte[] zip = res.getContentAsByteArray();
        assertThat(zip[0]).isEqualTo((byte) 'P');
        assertThat(zip[1]).isEqualTo((byte) 'K');

        Resp empty = call(get("/api/reports/bundle").param("period", "1999-01"), filler, null);
        assertThat(empty.http()).isEqualTo(404);
    }
}
