package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 租户授权期：未生效 / 停用 / 到期三种状态都必须阻断登录，
 * 到期后读接口同样拒绝（无宽限期）。
 */
class TenantLicenseTest extends ApiTestSupport {

    private static final String PLATFORM_PHONE = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.carbonbal.platform.TenantLicenseJob licenseJob;

    private record Tenant(long orgId, String phone, String tempPassword) {}

    private Tenant createTenant(String tag, LocalDate start, LocalDate end) throws Exception {
        String plat = login(PLATFORM_PHONE, PLATFORM_PWD);
        String ts = String.valueOf(System.currentTimeMillis() % 10000);
        String phone = "1396000" + String.format("%04d", (System.currentTimeMillis() + tag.hashCode()) % 10000);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "授权期租户 " + tag + " " + ts);
        body.put("code", "TL-" + tag + "-" + ts);
        body.put("adminPhone", phone);
        body.put("adminName", "授权管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 5);
        if (start != null) body.put("licenseStartAt", start.toString());
        if (end != null) body.put("licenseEndAt", end.toString());
        Resp r = call(post("/api/platform/tenants"), plat, body);
        assertThat(r.http()).as("开通租户: " + r.json()).isEqualTo(200);
        return new Tenant(r.body().path("orgId").asLong(), phone, r.body().path("tempPassword").asText());
    }

    private Resp loginTenant(Tenant t) throws Exception {
        return attemptLogin(t.phone(), t.tempPassword(), false);
    }

    @Test
    void pendingTenantBlocksLogin() throws Exception {
        Tenant t = createTenant("pending", LocalDate.now().plusDays(5), LocalDate.now().plusYears(1));
        Resp r = loginTenant(t);
        assertThat(r.http()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("TENANT_PENDING");
    }

    @Test
    void suspendedTenantBlocksLoginAndResumeRestores() throws Exception {
        Tenant t = createTenant("susp", null, null);
        String plat = login(PLATFORM_PHONE, PLATFORM_PWD);

        Resp ok = loginTenant(t);
        assertThat(ok.http()).as("正常租户可登录").isEqualTo(200);
        String token = ok.body().path("token").asText();

        assertThat(call(post("/api/platform/tenants/" + t.orgId() + "/suspend"), plat, Map.of()).http())
                .isEqualTo(200);
        Resp blocked = loginTenant(t);
        assertThat(blocked.http()).isEqualTo(423);
        assertThat(blocked.code()).isEqualTo("TENANT_SUSPENDED");
        // 已登录会话的读请求同样被拒
        Resp read = call(get("/api/overview"), token, null);
        assertThat(read.http()).isEqualTo(423);

        assertThat(call(post("/api/platform/tenants/" + t.orgId() + "/resume"), plat, Map.of()).http())
                .isEqualTo(200);
        assertThat(loginTenant(t).http()).as("恢复后可登录").isEqualTo(200);
    }

    @Test
    void expiredTenantBlocksReadAndWrite() throws Exception {
        Tenant t = createTenant("expire", null, null);
        Resp ok = loginTenant(t);
        assertThat(ok.http()).isEqualTo(200);
        String token = ok.body().path("token").asText();

        // 直接把到期时间改到昨天，模拟自然到期（接口本身不允许填过去日期）
        jdbc.update("update org set expire_at = ? where id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)), t.orgId());
        licenseJob.runOnce();

        Resp read = call(get("/api/overview"), token, null);
        assertThat(read.http()).as("到期后读接口也拒绝").isEqualTo(403);
        assertThat(read.code()).isEqualTo("TENANT_EXPIRED");

        Resp relogin = loginTenant(t);
        assertThat(relogin.http()).isEqualTo(403);
        assertThat(relogin.code()).isEqualTo("TENANT_EXPIRED");

        // 续期后恢复
        String plat = login(PLATFORM_PHONE, PLATFORM_PWD);
        JsonNode renewed = call(post("/api/platform/tenants/" + t.orgId() + "/renew"), plat, Map.of("months", 12)).body();
        assertThat(renewed.path("status").asText()).isEqualTo("active");
        assertThat(loginTenant(t).http()).isEqualTo(200);
    }

    @Test
    void superAdminCanEnterExpiredTenant() throws Exception {
        Tenant t = createTenant("enter", null, null);
        jdbc.update("update org set status = 'expired' where id = ?", t.orgId());
        String plat = login(PLATFORM_PHONE, PLATFORM_PWD);

        Resp entered = call(post("/api/auth/enter-tenant"), plat, Map.of("orgId", t.orgId()));
        assertThat(entered.http()).isEqualTo(200);
        String imp = entered.body().path("token").asText();
        assertThat(entered.body().path("user").path("impersonating").asBoolean()).isTrue();

        // 代管令牌在已到期租户内仍可读写
        assertThat(call(get("/api/overview"), imp, null).http()).isEqualTo(200);

        Resp me = call(get("/api/auth/me"), imp, null);
        assertThat(me.body().path("user").path("impersonating").asBoolean()).isTrue();
        assertThat(me.body().path("user").path("role").asText()).isEqualTo("admin");

        // 退出后代管标识消失
        Resp exited = call(post("/api/auth/exit-tenant"), imp, Map.of());
        assertThat(exited.http()).isEqualTo(200);
        assertThat(exited.body().path("user").path("role").asText()).isEqualTo("platform_admin");
    }
}
