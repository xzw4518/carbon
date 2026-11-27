package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 跨租户数据隔离：页面权限之外，行级隔离必须保证 A 企业看不到、改不了 B 企业的数据。
 * 覆盖列表、详情写入、导出三条最容易漏的路径。
 */
class TenantIsolationTest extends ApiTestSupport {

    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    private record Tenant(long orgId, String phone, String tempPassword) {}

    private Tenant newTenant(String tag) throws Exception {
        String plat = login(PLATFORM, PLATFORM_PWD);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String phone = "138" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        Map<String, Object> body = new HashMap<>();
        body.put("name", "隔离租户 " + tag + " " + sfx);
        body.put("code", "ISO-" + tag + "-" + sfx);
        body.put("adminPhone", phone);
        body.put("adminName", "隔离管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 5);
        Resp r = call(post("/api/platform/tenants"), plat, body);
        assertThat(r.http()).as("开通隔离租户: " + r.json()).isEqualTo(200);
        return new Tenant(r.body().path("orgId").asLong(), phone, r.body().path("tempPassword").asText());
    }

    private String tenantAdmin(Tenant t) throws Exception {
        String token = login(t.phone(), t.tempPassword());
        assertThat(call(post("/api/auth/password/change"), token, Map.of(
                "oldPassword", t.tempPassword(), "newPassword", STRONG)).http()).isEqualTo(200);
        return login(t.phone(), STRONG);
    }

    @Test
    void tenantDataIsInvisibleAndImmutableAcrossTenants() throws Exception {
        Tenant b = newTenant("B");
        String adminB = tenantAdmin(b);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String productName = "隔离产品" + sfx;
        Resp created = call(post("/api/products"), adminB, Map.of(
                "name", productName, "code", "ISO-P-" + sfx,
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(created.http()).as("B 建产品: " + created.json()).isEqualTo(200);
        long productId = created.body().path("id").asLong();

        // A 企业（演示租户）管理员
        String adminA = login("admin", "Admin@2026!Pass");

        // 1) 列表：按名称搜不到 B 的数据
        JsonNode list = call(get("/api/products?keyword=" + URLEncoder.encode(productName, StandardCharsets.UTF_8)),
                adminA, null).body();
        assertThat(list.path("total").asLong()).as("A 不应搜到 B 的产品").isZero();

        // 2) 写入：跨租户 PATCH 视为不存在
        Resp patch = call(put("/api/products/" + productId), adminA, Map.of(
                "name", "被篡改", "code", "ISO-P-" + sfx,
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(patch.http()).as("跨租户修改应 404").isEqualTo(404);

        // 3) 导出：A 导出的产品 CSV 不含 B 的产品
        String csv = mvc.perform(get("/api/export?resource=products")
                        .header("Authorization", "Bearer " + adminA))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain(productName);

        // 4) 反向：B 也看不到 A 的种子产品
        JsonNode listB = call(get("/api/products?keyword=" + URLEncoder.encode("精密轴承座", StandardCharsets.UTF_8)),
                adminB, null).body();
        assertThat(listB.path("total").asLong()).as("B 不应搜到 A 的产品").isZero();

        // 5) 审计也隔离：B 的审计里看不到 A 的操作人
        JsonNode auditB = call(get("/api/audit-logs?size=50"), adminB, null).body();
        for (JsonNode row : auditB.path("rows")) {
            assertThat(row.path("actor").asText()).isNotEqualTo("13800000001");
        }
        // 6) B 的页面集合与角色独立：只有一个默认角色
        JsonNode rolesB = call(get("/api/roles"), adminB, null).body();
        assertThat(rolesB.size()).as("新租户只有默认角色").isEqualTo(1);
        assertThat(rolesB.get(0).path("defaultRole").asBoolean()).isTrue();
        // P1-1：默认角色只自动拥有"非特权页面"，集团汇总需管理员显式授予
        assertThat(rolesB.get(0).path("pages").asInt())
                .as("默认角色页面数 = 全部页面 - 特权页面")
                .isEqualTo(com.carbonbal.identity.PageCatalog.DEFAULT_ROLE_CODES.size());
        JsonNode pagesOfB = call(get("/api/roles/" + rolesB.get(0).path("roleId").asLong()), adminB, null).body();
        assertThat(pagesOfB.path("pages").toString())
                .as("默认角色不得包含集团汇总（特权页面）")
                .doesNotContain("group.overview");
    }
}
