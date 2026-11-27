package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 角色只控页面：能进页面即可用，未授权页面接口与快照都不下发。 */
class RoleApiTest extends ApiTestSupport {

    private static final String ADMIN = "Admin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    @Test
    void roleControlsPagesAndBootstrap() throws Exception {
        String admin = login("admin", ADMIN);
        String suffix = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin, Map.of(
                "name", "产值只读" + suffix, "pages", List.of("product.list")));
        assertThat(role.http()).isEqualTo(200);
        assertThat(role.body().path("pages").size()).isEqualTo(2); // product.list + 自动补的 home

        String phone = "1399" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "产值专员" + suffix,
                "userType", "user", "roleId", role.body().path("roleId").asLong()));
        assertThat(created.http()).isEqualTo(200);
        String temp = created.body().path("tempPassword").asText();

        String token = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), token, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        token = login(phone, STRONG);

        // 有页面：可读
        assertThat(call(get("/api/products"), token, null).http()).isEqualTo(200);
        // 无页面：接口拒绝
        Resp denied = call(get("/api/suppliers"), token, null);
        assertThat(denied.http()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("PAGE_FORBIDDEN");

        // 快照只给有权限的部分
        JsonNode snap = call(get("/api/bootstrap"), token, null).body();
        assertThat(snap.has("products")).isTrue();
        assertThat(snap.has("suppliers")).isFalse();

        // 角色表单里看不到 /api/roles
        assertThat(call(get("/api/roles"), token, null).http()).isEqualTo(403);
    }

    @Test
    void platformAdminHasNoBusinessPages() throws Exception {
        String plat = login("13900000000", "Superadmin@2026!Pass");
        JsonNode me = call(get("/api/auth/me"), plat, null).body();
        assertThat(me.path("user").path("pages").size()).as("平台账号不应有业务页面").isZero();
        assertThat(me.path("user").path("role").asText()).isEqualTo("platform_admin");
        // 业务接口仍由角色注解拦截
        assertThat(call(get("/api/products"), plat, null).http()).isEqualTo(403);
        assertThat(call(get("/api/platform/tenants"), plat, null).http()).isEqualTo(200);
    }

    @Test
    void nonDefaultTenantUserGetsOnlyRolePages() throws Exception {
        // 回归：签发令牌时没有租户上下文，若 pagesOf 被行级隔离加上 org_id=1，
        // 非 1 号租户的普通用户会拿到空页面集合，而空集合曾被当成“全部页面”。
        String plat = login("13900000000", "Superadmin@2026!Pass");
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String adminPhone = "1390000" + String.format("%04d", System.nanoTime() % 10000);
        Resp tenant = call(post("/api/platform/tenants"), plat, Map.of(
                "name", "跨租户角色租户 " + sfx, "code", "ROLE-T-" + sfx,
                "adminPhone", adminPhone, "adminName", "跨租户管理员",
                "plan", "basic", "seatLimit", 5));
        assertThat(tenant.http()).as("开通租户: " + tenant.json()).isEqualTo(200);
        String tphoneTemp = tenant.body().path("tempPassword").asText();
        String tadmin = login(adminPhone, tphoneTemp);
        // 新租户管理员首次登录必须改密后才能调用其他接口
        assertThat(call(post("/api/auth/password/change"), tadmin, Map.of(
                "oldPassword", tphoneTemp, "newPassword", STRONG)).http()).isEqualTo(200);
        tadmin = login(adminPhone, STRONG);

        Resp role = call(post("/api/roles"), tadmin, Map.of(
                "name", "报告只读" + sfx, "pages", List.of("report.carbon")));
        assertThat(role.http()).as("建角色: " + role.json()).isEqualTo(200);
        long roleId = role.body().path("roleId").asLong();

        String phone = "1390001" + String.format("%04d", System.nanoTime() % 10000);
        Resp created = call(post("/api/users"), tadmin, Map.of(
                "phone", phone, "displayName", "跨租户只读", "userType", "user", "roleId", roleId));
        assertThat(created.http()).as("建用户: " + created.json()).isEqualTo(200);
        String temp = created.body().path("tempPassword").asText();

        String token = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), token, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        token = login(phone, STRONG);

        JsonNode me = call(get("/api/auth/me"), token, null).body().path("user");
        assertThat(me.path("pages").size()).as("只应有 home + 报告查询").isEqualTo(2);
        assertThat(me.path("pages").toString()).contains("report.carbon").doesNotContain("*");

        assertThat(call(get("/api/reports"), token, null).http()).isEqualTo(200);
        assertThat(call(get("/api/suppliers"), token, null).http()).isEqualTo(403);
    }

    @Test
    void defaultRoleCannotBeDeleted() throws Exception {
        String admin = login("admin", ADMIN);
        JsonNode roles = call(get("/api/roles"), admin, null).body();
        long defaultId = -1;
        for (JsonNode r : roles) {
            if (r.path("defaultRole").asBoolean()) defaultId = r.path("roleId").asLong();
        }
        assertThat(defaultId).isPositive();
        Resp del = call(delete("/api/roles/" + defaultId), admin, null);
        assertThat(del.http()).isEqualTo(409);
        assertThat(del.code()).isEqualTo("ROLE_DEFAULT");
    }

    @Test
    void roleInUseCannotBeDeleted() throws Exception {
        String admin = login("admin", ADMIN);
        String suffix = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin, Map.of(
                "name", "在用角色" + suffix, "pages", List.of("product.list")));
        long roleId = role.body().path("roleId").asLong();

        String phone = "1395" + String.format("%07d", System.nanoTime() % 10_000_000L);
        assertThat(call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "在用用户" + suffix,
                "userType", "user", "roleId", roleId)).http()).isEqualTo(200);

        Resp del = call(delete("/api/roles/" + roleId), admin, null);
        assertThat(del.http()).isEqualTo(409);
        assertThat(del.code()).isEqualTo("ROLE_IN_USE");
        assertThat(del.body().path("detail").path("users").asInt()).isEqualTo(1);
    }

    @Test
    void rolePagesMustExistAndNameUnique() throws Exception {
        String admin = login("admin", ADMIN);
        String suffix = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp bad = call(post("/api/roles"), admin, Map.of(
                "name", "非法页面" + suffix, "pages", List.of("no.such.page")));
        assertThat(bad.http()).isEqualTo(400);

        Resp first = call(post("/api/roles"), admin, Map.of(
                "name", "重名角色" + suffix, "pages", List.of("home")));
        assertThat(first.http()).isEqualTo(200);
        Resp dup = call(post("/api/roles"), admin, Map.of(
                "name", "重名角色" + suffix, "pages", List.of("home")));
        assertThat(dup.http()).isEqualTo(409);
        assertThat(dup.code()).isEqualTo("DUPLICATE");
    }
}
