package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 会话与多租户：一个人属于两家企业时的选择/切换，以及角色变更后旧令牌立即失效。
 * 这两条是“权限改了但旧会话还在”的高风险路径。
 */
class SessionTenantTest extends ApiTestSupport {

    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";
    private static final String ADMIN = "Admin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    @Test
    void userInTwoTenantsMustChooseAndCanSwitch() throws Exception {
        // 平台开通第二家租户，把演示管理员加进去
        String plat = login(PLATFORM, PLATFORM_PWD);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "第二租户 " + sfx);
        body.put("code", "SEC-" + sfx);
        body.put("adminPhone", "13800000001");
        body.put("adminName", "演示管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 5);
        Resp tenant = call(post("/api/platform/tenants"), plat, body);
        assertThat(tenant.http()).as("开通第二租户: " + tenant.json()).isEqualTo(200);
        long orgId = tenant.body().path("orgId").asLong();
        assertThat(tenant.body().path("existingAccount").asBoolean()).as("复用已有账号: " + tenant.json()).isTrue();
        assertThat(tenant.body().path("tempPassword").isMissingNode()
                || tenant.body().path("tempPassword").isNull()).isTrue();

        // 同一手机号加入第二家企业后：登录仍落上次企业，但可切换
        Resp login = attemptLogin("13800000001", ADMIN, false);
        assertThat(login.http()).isEqualTo(200);
        String tokenA = login.body().path("token").asText();
        assertThat(login.body().path("needTenantSelect").asBoolean()).isFalse();
        assertThat(login.body().path("user").path("orgId").asLong()).isEqualTo(1L);

        Resp switched = call(post("/api/auth/switch-tenant"), tokenA, Map.of("orgId", orgId));
        assertThat(switched.http()).isEqualTo(200);
        String tokenB = switched.body().path("token").asText();
        assertThat(switched.body().path("user").path("orgId").asLong()).isEqualTo(orgId);
        assertThat(switched.body().path("user").path("role").asText()).isEqualTo("admin");
        assertThat(switched.body().path("user").path("userType").asText()).isEqualTo("tenant_admin");
        assertThat(switched.body().path("user").path("roleName").asText()).isEqualTo("租户管理员");

        // 新租户里没有业务数据
        JsonNode productsB = call(get("/api/products"), tokenB, null).body();
        assertThat(productsB.path("total").asLong()).as("新租户无产品").isZero();

        // 切回演示租户
        Resp back = call(post("/api/auth/switch-tenant"), tokenB, Map.of("orgId", 1));
        assertThat(back.http()).isEqualTo(200);
        String tokenBack = back.body().path("token").asText();
        JsonNode productsA = call(get("/api/products"), tokenBack, null).body();
        assertThat(productsA.path("total").asLong()).as("切回后能看到演示租户的产品").isPositive();
    }

    @Test
    void roleChangeRevokesOldToken() throws Exception {
        String admin = login("admin", ADMIN);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin, Map.of(
                "name", "会话角色" + sfx, "pages", List.of("product.list")));
        assertThat(role.http()).isEqualTo(200);
        long roleId = role.body().path("roleId").asLong();

        String phone = "1373" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "会话用户" + sfx,
                "userType", "user", "roleId", roleId));
        String temp = created.body().path("tempPassword").asText();
        String token = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), token, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        token = login(phone, STRONG);
        assertThat(call(get("/api/products"), token, null).http()).isEqualTo(200);

        // 管理员改角色页面 → 该角色下成员旧令牌失效
        Resp updated = call(put("/api/roles/" + roleId), admin, Map.of("pages", List.of("product.list", "supplier.list")));
        assertThat(updated.http()).isEqualTo(200);

        Resp old = call(get("/api/products"), token, null);
        assertThat(old.http()).as("旧令牌应失效").isEqualTo(401);
        assertThat(old.code()).isEqualTo("SESSION_REVOKED");

        // 重新登录后按新页面生效
        String fresh = login(phone, STRONG);
        assertThat(call(get("/api/suppliers"), fresh, null).http()).isEqualTo(200);
    }
}
