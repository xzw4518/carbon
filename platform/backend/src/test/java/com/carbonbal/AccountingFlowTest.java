package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 断言 3、4、6、7、9、10：核算口径、锁定、PDF 三处一致、因子版本、解锁、因子优先级。
 * <p>
 * 顺序说明（P1-10）：本类是"链路设计"而非"顺序污染"——每个方法依赖上一方法算出的
 * {@code version/calcVersion/total}，拆成独立 fixture 需要重写 7 条用例的取数逻辑，
 * 风险高于收益（架构方案 §6 明确本轮不做）。因此保留 {@code @Order}，
 * 但把 e2e 的"自愈"逻辑前移到 {@link org.junit.jupiter.api.BeforeAll}：
 * 每轮开始先把 2026-10 / 2026-11 复位为 draft，保证单类运行（{@code -Dtest=AccountingFlowTest}）
 * 与全套运行结果一致，不再被上一轮残留的 submitted/locked 状态带崩。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountingFlowTest extends ApiTestSupport {

    private static final String PERIOD = "2026-11";
    /** 复核职责分离用例使用的核算期。 */
    private static final String SECOND_PERIOD = "2026-10";
    private static int version;
    private static int calcVersion;
    private static BigDecimal total;
    private static BigDecimal scope1;
    private static BigDecimal scope2;
    private static Long firstReportId;
    private static String firstReportSha;

    /** 自愈：把两个核算期复位为 draft（等价于 e2e 脚本里的"重跑前重置"）。 */
    @BeforeAll
    void resetPeriodsToDraft() {
        for (String period : java.util.List.of(PERIOD, SECOND_PERIOD)) {
            jdbc.update("delete from report where period_id in "
                    + "(select id from accounting_period where org_id = ? and period = ?)", DEMO_ORG_ID, period);
            jdbc.update("delete from accounting_line where period_id in "
                    + "(select id from accounting_period where org_id = ? and period = ?)", DEMO_ORG_ID, period);
            jdbc.update("delete from period_unlock where period_id in "
                    + "(select id from accounting_period where org_id = ? and period = ?)", DEMO_ORG_ID, period);
            jdbc.update("update accounting_period set status = 'draft', calc_version = 0, "
                            + "scope1 = null, scope2 = null, total = null, "
                            + "locked_at = null, locked_by = null, "
                            + "submitted_by = null, submitted_at = null, "
                            + "reviewed_by = null, reviewed_at = null, "
                            + "approved_by = null, approved_at = null, updated_at = current_timestamp "
                            + "where org_id = ? and period = ?", DEMO_ORG_ID, period);
        }
    }

    @Test
    @Order(0)
    void invalidPeriodAndVersionAreRejectedAs400() throws Exception {
        String filler = login("filler", "Test@2026!Pass");

        for (String bad : new String[]{"2026-13", "2026-00", "2026-1", "abc"}) {
            Resp calc = call(post("/api/accounting-periods/" + bad + "/calculate"), filler, null);
            assertThat(calc.http()).as("非法核算期 %s 应为 400", bad).isEqualTo(400);
            assertThat(calc.code()).isEqualTo("VALIDATION");
        }

        Resp review = call(post("/api/accounting-periods/" + PERIOD + "/review"), filler, Map.of("version", "abc"));
        assertThat(review.http()).as("review 非法版本号应为 400").isEqualTo(400);
        assertThat(review.code()).isEqualTo("VALIDATION");

        Resp approve = call(post("/api/accounting-periods/" + PERIOD + "/approve"), filler, Map.of("version", "1.5"));
        assertThat(approve.http()).as("approve 非法版本号应为 400").isEqualTo(400);
        assertThat(approve.code()).isEqualTo("VALIDATION");
    }

    @Test
    @Order(1)
    void calculateUsesCustomerFactorFirst() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp r = call(post("/api/accounting-periods/" + PERIOD + "/calculate"), filler, null);
        assertThat(r.http()).isEqualTo(200);

        JsonNode p = r.body();
        version = p.path("version").asInt();
        calcVersion = p.path("calcVersion").asInt();
        total = p.path("total").decimalValue();

        BigDecimal s1 = p.path("scope1").decimalValue();
        BigDecimal s2 = p.path("scope2").decimalValue();
        scope1 = s1;
        scope2 = s2;
        assertThat(total).as("total = scope1 + scope2").isEqualByComparingTo(s1.add(s2));

        BigDecimal sum = BigDecimal.ZERO;
        boolean gasChecked = false;
        for (JsonNode l : p.path("lines")) {
            sum = sum.add(l.path("emission").decimalValue());
            if (l.path("deviceName").asText().contains("燃气")) {
                assertThat(l.path("factorValue").decimalValue())
                        .as("天然气应取客户自定义因子 21.84")
                        .isEqualByComparingTo("21.84");
                gasChecked = true;
            }
        }
        assertThat(gasChecked).isTrue();
        assertThat(sum).as("明细合计 = total").isEqualByComparingTo(total);
    }

    @Test
    @Order(2)
    void pdfMatchesApiTotal() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        // 未提交（draft/calculated）不允许生成正式报告
        Resp denied = call(post("/api/reports"), filler, Map.of("period", PERIOD));
        assertThat(denied.http()).as("未提交不得生成正式报告").isEqualTo(409);
        assertThat(denied.code()).isEqualTo("PERIOD_LOCKED");

        Resp sub = call(post("/api/accounting-periods/" + PERIOD + "/submit"), filler, Map.of("version", version));
        assertThat(sub.http()).isEqualTo(200);
        version = sub.body().path("version").asInt();

        Resp gen = call(post("/api/reports"), filler, Map.of("period", PERIOD));
        assertThat(gen.http()).isEqualTo(200);
        firstReportId = gen.body().path("id").asLong();
        firstReportSha = gen.body().path("sha256").asText();
        assertThat(firstReportSha).hasSize(64);

        byte[] pdf = mvc.perform(get("/api/reports/" + firstReportId + "/download")
                        .header("Authorization", "Bearer " + filler))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(sha256(pdf)).as("下载文件指纹与生成记录一致").isEqualTo(firstReportSha);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("scope1=" + scope1.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            assertThat(text).contains("scope2=" + scope2.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            assertThat(text).contains("total=" + total.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
        }
    }

    @Test
    @Order(3)
    void submitLockBlocksWriting() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String admin = login("admin", "Admin@2026!Pass");

        Resp pv = call(get("/api/accounting-periods/" + PERIOD), filler, null);
        version = pv.body().path("version").asInt();
        if (!"submitted".equals(pv.body().path("status").asText())) {
            Resp sub = call(post("/api/accounting-periods/" + PERIOD + "/submit"), filler, Map.of("version", version));
            assertThat(sub.http()).isEqualTo(200);
            assertThat(sub.body().path("status").asText()).isEqualTo("submitted");
            version = sub.body().path("version").asInt();
        }

        // 未复核不得锁定
        Resp early = call(post("/api/accounting-periods/" + PERIOD + "/lock"), admin, Map.of("version", version));
        assertThat(early.http()).as("未复核不得锁定").isEqualTo(400);

        String reviewer = login("reviewer", "Test@2026!Pass");
        Resp review = call(post("/api/accounting-periods/" + PERIOD + "/review"), reviewer,
                Map.of("version", version, "remark", "数据核对无误"));
        assertThat(review.http()).isEqualTo(200);
        assertThat(review.body().path("status").asText()).isEqualTo("reviewed");
        version = review.body().path("version").asInt();

        String approver = login("approver", "Test@2026!Pass");
        Resp approve = call(post("/api/accounting-periods/" + PERIOD + "/approve"), approver,
                Map.of("version", version, "remark", "同意"));
        assertThat(approve.http()).isEqualTo(200);
        assertThat(approve.body().path("status").asText()).isEqualTo("approved");
        version = approve.body().path("version").asInt();

        Resp lock = call(post("/api/accounting-periods/" + PERIOD + "/lock"), admin, Map.of("version", version));
        assertThat(lock.http()).isEqualTo(200);
        assertThat(lock.body().path("status").asText()).isEqualTo("locked");
        version = lock.body().path("version").asInt();

        Resp list = call(get("/api/device-readings").param("deviceId", "1"), filler, null);
        long rid = list.body().get(0).path("id").asLong();
        int rv = list.body().get(0).path("version").asInt();
        Resp patchRes = call(put("/api/device-readings/" + rid), filler, Map.of("version", rv, "qty", 1));
        assertThat(patchRes.http()).as("锁定后写入应被拒绝").isEqualTo(409);
        assertThat(patchRes.body().path("message").asText()).contains("已锁定");
    }

    @Test
    @Order(3)
    void submitterCannotReviewOwnPeriod() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        String period = SECOND_PERIOD;
        Resp calc = call(post("/api/accounting-periods/" + period + "/calculate"), admin, null);
        assertThat(calc.http()).isEqualTo(200);
        int v = calc.body().path("version").asInt();
        Resp sub = call(post("/api/accounting-periods/" + period + "/submit"), admin, Map.of("version", v));
        assertThat(sub.http()).isEqualTo(200);
        int v2 = sub.body().path("version").asInt();
        Resp review = call(post("/api/accounting-periods/" + period + "/review"), admin,
                Map.of("version", v2, "remark", "自审"));
        assertThat(review.http()).as("编制人不得复核自己").isEqualTo(409);
    }

    @Test
    @Order(4)
    void unlockRequiresReasonAndRestoresDraft() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");

        Resp noReason = call(post("/api/accounting-periods/" + PERIOD + "/unlock"), admin,
                Map.of("version", version, "reason", ""));
        assertThat(noReason.http()).isEqualTo(400);

        Resp unlock = call(post("/api/accounting-periods/" + PERIOD + "/unlock"), admin,
                Map.of("version", version, "reason", "客户更正 11 月燃气表读数"));
        assertThat(unlock.http()).isEqualTo(200);
        assertThat(unlock.body().path("status").asText()).isEqualTo("draft");
        assertThat(unlock.body().path("unlocks").size()).isEqualTo(1);
        assertThat(unlock.body().path("unlocks").get(0).path("reason").asText()).contains("更正");
        // P2-1：不再写自审批人——requestedBy 有值，approvedBy 不落库（null 被 non_null 序列化策略省略）
        assertThat(unlock.body().path("unlocks").get(0).path("requestedBy").asText()).isNotBlank();
        assertThat(unlock.body().path("unlocks").get(0).has("approvedBy"))
                .as("单人解锁不得伪造审批人").isFalse();
        assertThat(unlock.body().path("selfApproved").asBoolean())
                .as("单人解锁必须显式标记，便于审计识别").isTrue();
        version = unlock.body().path("version").asInt();

        Resp recalc = call(post("/api/accounting-periods/" + PERIOD + "/calculate"), admin, null);
        assertThat(recalc.http()).isEqualTo(200);
        assertThat(recalc.body().path("calcVersion").asInt()).as("重算生成新版本").isEqualTo(calcVersion + 1);
        version = recalc.body().path("version").asInt();
    }

    @Test
    @Order(5)
    void customerFactorChangeProducesNewVersionButKeepsOldReport() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        BigDecimal before = total;

        Resp created = call(post("/api/emission-factors"), admin, Map.of(
                "category", "electricity", "fuel", "外购电力", "unit", "MWh",
                "value", 0.62, "sourceDoc", "测试用客户实测因子", "scope", "customer",
                "version", "t1", "effectiveFrom", "2024-01-01"));
        assertThat(created.http()).isEqualTo(200);
        long fid = created.body().path("id").asLong();
        assertThat(created.body().path("reviewStatus").asText()).isEqualTo("pending");

        Resp review = call(post("/api/emission-factors/" + fid + "/review"), admin, Map.of("approve", true));
        assertThat(review.http()).isEqualTo(200);
        assertThat(review.body().path("reviewStatus").asText()).isEqualTo("approved");

        Resp recalc = call(post("/api/accounting-periods/" + PERIOD + "/calculate"), admin, null);
        assertThat(recalc.http()).isEqualTo(200);
        BigDecimal after = recalc.body().path("total").decimalValue();
        assertThat(after).as("客户因子 0.62 应高于地方 0.5617，总量上升").isGreaterThan(before);

        // 重新提交后才允许生成正式报告
        Resp pv5 = call(get("/api/accounting-periods/" + PERIOD), admin, null);
        if (!"submitted".equals(pv5.body().path("status").asText())) {
            assertThat(call(post("/api/accounting-periods/" + PERIOD + "/submit"), admin,
                    Map.of("version", pv5.body().path("version").asInt())).http()).isEqualTo(200);
        }

        Resp gen2 = call(post("/api/reports"), admin, Map.of("period", PERIOD));
        assertThat(gen2.http()).isEqualTo(200);
        assertThat(gen2.body().path("sha256").asText()).isNotEqualTo(firstReportSha);

        byte[] oldPdf = mvc.perform(get("/api/reports/" + firstReportId + "/download")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(oldPdf)).as("旧报告内容不应被重算改写").isEqualTo(firstReportSha);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
