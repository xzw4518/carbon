package com.carbonbal.common;

import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 期间数据守卫：任何改动核算数据的入口先过这里。
 *
 * <p>状态语义：draft / calculated 可自由修改；submitted / reviewed 表示已进入审批链，
 * 此时修改数据会让审批结论失真，因此写入前先把期间回退到 calculated 并留审计；
 * approved / locked 表示已定稿归档，直接拒绝，须走解锁或重新开启。
 */
@Component
public class PeriodGuard {
    private static final List<String> IN_REVIEW = List.of("submitted", "reviewed");
    private static final List<String> FINAL = List.of("approved", "locked");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PeriodGuard(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void assertEditable(String period) {
        if (period == null || period.isBlank()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, status from accounting_period where org_id = ? and period = ?",
                CurrentUser.orgId(), period);
        if (rows.isEmpty()) return;

        Long id = ((Number) rows.get(0).get("id")).longValue();
        String status = String.valueOf(rows.get(0).get("status"));

        if ("locked".equals(status)) {
            throw BizException.locked("核算期 " + period + " 已锁定，禁止修改数据；如需更正请走解锁审批");
        }
        if ("approved".equals(status)) {
            throw BizException.locked("核算期 " + period + " 已批准，禁止修改数据；如需更正请先解锁或重新开启核算");
        }
        if (IN_REVIEW.contains(status)) {
            // 已提交/已复核的数据被改动后，原审批结论不再成立，自动回退到待提交
            int n = jdbc.update(
                    "update accounting_period set status = 'calculated', "
                            + "submitted_by = null, submitted_at = null, "
                            + "reviewed_by = null, reviewed_at = null, "
                            + "version = version + 1, updated_at = now() "
                            + "where id = ? and org_id = ? and status = ?",
                    id, CurrentUser.orgId(), status);
            if (n > 0) {
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("status", status);
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("status", "calculated");
                after.put("reason", "核算数据被修改，审批链自动回退");
                audit.record("accounting_period", id, "rollback", before, after);
            }
        }
    }
}
