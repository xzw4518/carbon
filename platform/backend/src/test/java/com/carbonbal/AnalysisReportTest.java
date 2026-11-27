package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** P4：分析六页聚合、首页概览、能源/足迹报告、报告删除权限。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisReportTest extends ApiTestSupport {

    private static final String PERIOD = "2026-11";
    private static Long energyReportId;
    private static Long footprintReportId;
    private static Long carbonReportId;

    @Test
    @Order(1)
    void overviewAggregatesHomePage() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp r = call(get("/api/overview").param("year", "2026"), filler, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("orgName").asText()).contains("华东精密");
        assertThat(r.body().path("kpis")).hasSize(4);
        assertThat(r.body().path("monthly")).hasSize(12);
        assertThat(r.body().path("quota").path("total").decimalValue()).isPositive();
        assertThat(r.body().path("todos").isArray()).isTrue();
        assertThat(r.body().path("alerts").isArray()).isTrue();
    }

    @Test
    @Order(2)
    void sixAnalysisPagesReturnKpisSeriesTable() throws Exception {
        String filler = login("filler", "Test@2026!Pass");

        Resp energy = call(get("/api/analysis/energy-consumption")
                .param("from", "2026-09").param("to", "2026-11"), filler, null);
        assertThat(energy.http()).isEqualTo(200);
        assertThat(energy.body().path("kpis")).hasSize(4);
        assertThat(energy.body().path("series").size()).isGreaterThanOrEqualTo(1);
        assertThat(energy.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(5);
        assertThat(energy.body().path("table").path("rows").get(0).path("tce").decimalValue()).isPositive();

        Resp carbon = call(get("/api/analysis/carbon-emission")
                .param("from", "2026-09").param("to", "2026-11"), filler, null);
        assertThat(carbon.http()).isEqualTo(200);
        assertThat(carbon.body().path("kpis").get(0).path("value").asText()).isNotBlank();
        assertThat(carbon.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(5);

        Resp eff = call(get("/api/analysis/energy-efficiency")
                .param("from", "2026-09").param("to", "2026-11"), filler, null);
        assertThat(eff.http()).isEqualTo(200);
        assertThat(eff.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(1);
        assertThat(eff.body().path("series")).hasSize(2);

        Resp flow = call(get("/api/analysis/energy-flow")
                .param("from", "2026-09").param("to", "2026-11"), filler, null);
        assertThat(flow.http()).isEqualTo(200);
        assertThat(flow.body().path("sankey").path("layers")).hasSize(3);
        assertThat(flow.body().path("sankey").path("links").size()).isPositive();

        Resp strategy = call(get("/api/analysis/strategy")
                .param("from", "2026-09").param("to", "2026-11"), filler, null);
        assertThat(strategy.http()).isEqualTo(200);
        assertThat(strategy.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(1);

        Resp quota = call(get("/api/analysis/quota").param("year", "2026"), filler, null);
        assertThat(quota.http()).isEqualTo(200);
        assertThat(quota.body().path("kpis")).hasSize(4);
        assertThat(quota.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(1);

        Resp bad = call(get("/api/analysis/energy-consumption")
                .param("from", "2026-12").param("to", "2026-01"), filler, null);
        assertThat(bad.http()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void footprintAndSupplyChainAnalysis() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp fp = call(get("/api/analysis/footprint").param("year", "2026"), filler, null);
        assertThat(fp.http()).isEqualTo(200);
        assertThat(fp.body().path("kpis")).hasSize(4);
        assertThat(fp.body().path("series").get(0).path("labels")).hasSize(3);
        assertThat(fp.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(1);

        Resp sc = call(get("/api/analysis/supply-chain"), filler, null);
        assertThat(sc.http()).isEqualTo(200);
        assertThat(sc.body().path("kpis")).hasSize(4);
        assertThat(sc.body().path("table").path("rows").size()).isGreaterThanOrEqualTo(4);

        Resp none = call(get("/api/analysis/footprint").param("productId", "99999"), filler, null);
        assertThat(none.http()).isEqualTo(200);
        assertThat(none.body().path("table").path("rows")).isEmpty();
    }

    @Test
    @Order(4)
    void energyReportPdfHasMachineSummary() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        // 草稿期不允许生成正式报告：先核算并提交本期间
        assertThat(call(post("/api/accounting-periods/" + PERIOD + "/calculate"), filler, null).http())
                .isEqualTo(200);
        Resp pv = call(get("/api/accounting-periods/" + PERIOD), filler, null);
        if (!java.util.Set.of("submitted", "reviewed", "approved", "locked").contains(pv.body().path("status").asText())) {
            Resp sub = call(post("/api/accounting-periods/" + PERIOD + "/submit"), filler,
                    Map.of("version", pv.body().path("version").asInt()));
            assertThat(sub.http()).isEqualTo(200);
        }
        Resp gen = call(post("/api/reports"), filler, Map.of("type", "energy", "period", PERIOD));
        assertThat(gen.http()).isEqualTo(200);
        energyReportId = gen.body().path("id").asLong();
        assertThat(gen.body().path("type").asText()).isEqualTo("energy");
        assertThat(gen.body().path("fileName").asText()).contains("能源利用报告");

        byte[] pdf = download(energyReportId, filler);
        assertThat(sha256(pdf)).isEqualTo(gen.body().path("sha256").asText());
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("type=energy").contains("period=" + PERIOD).contains("tce=");
        }

        Resp list = call(get("/api/reports").param("type", "energy").param("period", PERIOD), filler, null);
        assertThat(list.body().path("rows").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(5)
    void footprintReportUsesFootprintId() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/footprints").param("keyword", "精密").param("size", "5"), filler, null);
        long fid = list.body().path("rows").get(0).path("id").asLong();

        Resp gen = call(post("/api/reports"), filler, Map.of("type", "footprint", "footprintId", fid));
        assertThat(gen.http()).isEqualTo(200);
        footprintReportId = gen.body().path("id").asLong();
        assertThat(gen.body().path("type").asText()).isEqualTo("footprint");
        assertThat(gen.body().path("refId").asLong()).isEqualTo(fid);

        byte[] pdf = download(footprintReportId, filler);
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("type=footprint").contains("boundary=").contains("footprint=");
        }

        Resp missing = call(post("/api/reports"), filler, Map.of("type", "footprint"));
        assertThat(missing.http()).isEqualTo(400);
    }

    @Test
    @Order(6)
    void carbonReportStillWorksAndDeleteIsAdminOnly() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String viewer = login("viewer", "Test@2026!Pass");
        String admin = login("admin", "Admin@2026!Pass");

        Resp calc = call(get("/api/accounting-periods/" + PERIOD), filler, null);
        assertThat(calc.http()).isEqualTo(200);
        assertThat(calc.body().path("calcVersion").asInt()).isPositive();

        Resp gen = call(post("/api/reports"), filler, Map.of("period", PERIOD));
        assertThat(gen.http()).isEqualTo(200);
        carbonReportId = gen.body().path("id").asLong();
        assertThat(gen.body().path("type").asText()).isEqualTo("carbon");
        assertThat(gen.body().path("calcVersion").asInt()).isPositive();

        Resp denied = call(delete("/api/reports/" + carbonReportId), viewer, null);
        assertThat(denied.http()).isEqualTo(403);

        assertThat(call(delete("/api/reports/" + carbonReportId), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/reports/" + carbonReportId + "/download"), admin, null).http())
                .isEqualTo(404);
    }

    private byte[] download(Long id, String token) throws Exception {
        var res = mvc.perform(get("/api/reports/" + id + "/download")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        byte[] bytes = res.getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
