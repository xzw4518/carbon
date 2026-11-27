package com.carbonbal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 测试清理的安全边界（P0-1 / P1-10）。
 * <p>
 * 清理语句会删除 {@code org_id <> 1} 的全部数据，因此必须证明三件事：
 * <ol>
 *   <li>运行环境是 H2 内存库（否则基类在 {@code @BeforeAll} 就该把整类判失败）；</li>
 *   <li>清理<b>真的</b>执行了（新建的测试租户确实被删掉，而不是静默失效）；</li>
 *   <li>清理不会碰演示租户（{@code org_id = 1}）的任何数据，且可以重复执行（幂等）。</li>
 * </ol>
 * 这里直接调用基类的 {@code cleanupTestTenants()}，比"跑完整套后肉眼看日志"更有证明力。
 */
class CleanupSafetyTest extends ApiTestSupport {

    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";

    /** 演示租户下会被清理语句"顺手"访问的表，逐项对比前后计数。 */
    private static final String[] DEMO_TABLES = {
            "device", "device_reading", "product", "product_footprint", "carbon_asset",
            "carbon_trade", "accounting_period", "accounting_line", "report", "period_unlock",
            "tenant_role", "tenant_role_page", "user_tenant", "facility", "supplier", "quota_year"
    };

    @Test
    void cleanupOnlyRunsOnH2() {
        assertThat(databaseProduct())
                .as("测试清理只允许在 H2 内存库运行")
                .isEqualToIgnoringCase("H2");
    }

    @Test
    void cleanupRemovesTestTenantButKeepsDemoTenantIntact() throws Exception {
        String plat = login(PLATFORM, PLATFORM_PWD);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String phone = "138" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TST 清理验证租户 " + sfx);
        body.put("code", "TST-CLN-" + sfx);
        body.put("adminPhone", phone);
        body.put("adminName", "清理验证管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 5);
        Resp tenant = call(post("/api/platform/tenants"), plat, body);
        assertThat(tenant.http()).as("开通测试租户: " + tenant.json()).isEqualTo(200);
        long orgId = tenant.body().path("orgId").asLong();

        Long roleCountBefore = jdbc.queryForObject(
                "select count(*) from tenant_role where org_id = ?", Long.class, orgId);
        assertThat(roleCountBefore).as("新租户应有默认角色").isGreaterThan(0L);

        Map<String, Long> demoBefore = demoTenantCounts();
        assertThat(demoBefore.get("device")).as("演示租户应有设备种子数据").isGreaterThan(0L);
        assertThat(demoBefore.get("user_tenant")).as("演示租户应有成员").isGreaterThan(0L);

        // 直接跑清理：应无异常（旧实现在 H2 下会因多表 DELETE 语法失败并被 catch 吞掉）
        cleanupTestTenants();

        Long orgLeft = jdbc.queryForObject("select count(*) from org where id = ?", Long.class, orgId);
        assertThat(orgLeft).as("清理必须真的删掉测试租户（不能再静默失效）").isZero();
        Long roleLeft = jdbc.queryForObject(
                "select count(*) from tenant_role where org_id = ?", Long.class, orgId);
        assertThat(roleLeft).as("测试租户的角色必须一并清理").isZero();

        Map<String, Long> demoAfter = demoTenantCounts();
        assertThat(demoAfter).as("cleanupTestTenants 不得删除 org_id=1 的任何数据").isEqualTo(demoBefore);

        // 幂等：第二次执行仍不抛异常、演示租户计数不变
        cleanupTestTenants();
        assertThat(demoTenantCounts()).isEqualTo(demoBefore);
    }

    /** org_id=1 的逐表计数（顺序稳定，便于整体比较）。 */
    private Map<String, Long> demoTenantCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : DEMO_TABLES) {
            Long n = jdbc.queryForObject("select count(*) from " + table + " where org_id = ?", Long.class,
                    DEMO_ORG_ID);
            out.put(table, n == null ? 0L : n);
        }
        return out;
    }
}
