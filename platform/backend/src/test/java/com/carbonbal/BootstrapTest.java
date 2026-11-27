package com.carbonbal;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.carbonbal.report.Report;
import com.carbonbal.report.ReportMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 原型前端启动快照：结构与 data.js 对齐，且按租户隔离。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootstrapTest extends ApiTestSupport {

    /** 报告行投影（P1-6）验证用：抓取 Mapper 实际下发的 select 列表。 */
    @SpyBean
    private ReportMapper reportMapper;

    private static final String BIG_NAME_PREFIX = "TST-BIG-REPORT-";
    private static final int BIG_ROWS = 10;
    private static final int BIG_CONTENT = 256 * 1024;

    @Test
    @Order(1)
    void snapshotHasPrototypeShape() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp r = call(get("/api/bootstrap").param("year", "2026"), filler, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("org").path("name").asText()).contains("华东精密");
        assertThat(r.body().path("products").size()).isGreaterThanOrEqualTo(5);
        assertThat(r.body().path("facilities").size()).isGreaterThanOrEqualTo(2);
        assertThat(r.body().path("facilities").get(0).path("children").size()).isGreaterThanOrEqualTo(1);
        assertThat(r.body().path("accounting").size()).isGreaterThanOrEqualTo(1);
        assertThat(r.body().path("kpi").path("energy").decimalValue()).isPositive();
        assertThat(r.body().path("quota").path("total").decimalValue()).isPositive();
        assertThat(r.body().path("monthly").size()).isEqualTo(12);
        assertThat(r.body().has("prodValue")).isFalse();
        assertThat(r.body().has("deviceData")).isFalse();
        assertThat(r.body().has("evidences")).isFalse();
        assertThat(r.body().has("trades")).isFalse();
        assertThat(r.body().path("reports").isArray()).isTrue();
    }

    @Test
    @Order(2)
    void snapshotRequiresLogin() throws Exception {
        int status = mvc.perform(get("/api/bootstrap")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    @Order(3)
    void viewerCanReadSnapshot() throws Exception {
        String viewer = login("viewer", "Test@2026!Pass");
        Resp r = call(get("/api/bootstrap"), viewer, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("org").path("short").asText()).isEqualTo("华东精密");
    }

    /**
     * P1-6：{@code /api/bootstrap} 的报告行不得读取 {@code report.content}（PDF BLOB）。
     *
     * <p>三重断言，任意一条失败都说明"投影"被改回全字段查询：
     * <ol>
     *   <li>Mapper 实际下发的 select 列表里没有 {@code content}；</li>
     *   <li>响应体不含 {@code content} 字段；</li>
     *   <li>塞入 10 份 256KB 报告后，响应体大小相对基线增长可忽略（≤ 4KB）。</li>
     * </ol>
     */
    @Test
    @Order(4)
    void reportRowsNeverSelectPdfContent() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        int baseline = call(get("/api/bootstrap").param("year", "2026"), admin, null)
                .json().toString().getBytes(StandardCharsets.UTF_8).length;

        Long periodId = jdbc.queryForObject(
                "select id from accounting_period where org_id = 1 and period = '2026-11'", Long.class);
        assertThat(periodId).as("演示租户 2026-11 核算期应存在").isNotNull();

        List<Long> ids = seedBigReports(periodId);
        try {
            Resp r = call(get("/api/bootstrap").param("year", "2026"), admin, null);
            assertThat(r.http()).isEqualTo(200);

            // ① Mapper 的 select 列表不含 content
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Report>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(reportMapper, atLeastOnce()).selectList(captor.capture());
            List<String> selects = new ArrayList<>();
            captor.getAllValues().forEach(w -> selects.add(String.valueOf(w.getSqlSelect())));
            assertThat(selects).as("reportRows 必须走列投影").anySatisfy(s -> assertThat(s).contains("file_name"));
            assertThat(selects).allSatisfy(s -> assertThat(s).doesNotContain("content"));

            // ② 响应体不含 PDF 内容字段
            String body = r.json().toString();
            assertThat(body).doesNotContain("\"content\"");

            // ③ 体积不随 PDF 增长
            int after = body.getBytes(StandardCharsets.UTF_8).length;
            assertThat(r.body().path("reports").size()).isGreaterThanOrEqualTo(BIG_ROWS);
            assertThat(after - baseline)
                    .as("10 份 %dKB 报告不应让 bootstrap 体积增长（基线 %d，之后 %d）", BIG_CONTENT / 1024, baseline, after)
                    .isLessThanOrEqualTo(4096);

            // 数据确实很大：确认测试数据有说服力（全量查询会读到这些字节）
            Long bigRows = jdbc.queryForObject(
                    "select count(*) from report where file_name like ? and length(content) >= ?",
                    Long.class, BIG_NAME_PREFIX + "%", BIG_CONTENT);
            assertThat(bigRows).isEqualTo((long) BIG_ROWS);
        } finally {
            ids.forEach(id -> jdbc.update("delete from report where id = ?", id));
        }
    }

    /** 直接落库造大报告：绕过 PDF 生成，专注验证查询投影。 */
    private List<Long> seedBigReports(Long periodId) {
        byte[] content = new byte[BIG_CONTENT];
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < BIG_ROWS; i++) {
            jdbc.update("""
                    insert into report
                      (org_id, report_type, period_id, period_label, calc_version, file_name, file_size,
                       sha256, content, disabled, generated_by, generated_at)
                    values (1, 'carbon', ?, '2026-11', 1, ?, ?, ?, ?, 0, 'test', ?)
                    """, periodId, BIG_NAME_PREFIX + i + ".pdf", BIG_CONTENT,
                    "0".repeat(64), content, LocalDateTime.now());
            ids.add(jdbc.queryForObject("select max(id) from report", Long.class));
        }
        return ids;
    }
}
