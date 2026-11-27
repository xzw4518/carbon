package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** P3 平台概览：KPI、30 日内到期预警、最近平台操作、到期提醒数据。 */
class PlatformOverviewTest extends ApiTestSupport {

    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";

    private record Tenant(long orgId, String name, String code, String phone, String tempPassword) {}

    private Tenant createTenant(String tag, int expireInDays) throws Exception {
        String plat = login(PLATFORM, PLATFORM_PWD);
        String ts = String.valueOf(System.currentTimeMillis() % 100000);
        String phone = "139" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        String name = "P3 租户 " + tag + " " + ts;
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("code", "P3-" + tag + "-" + ts);
        body.put("adminPhone", phone);
        body.put("adminName", "P3 管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 3);
        body.put("licenseStartAt", LocalDate.now().toString());
        body.put("licenseEndAt", LocalDate.now().plusDays(expireInDays).toString());
        Resp r = call(post("/api/platform/tenants"), plat, body);
        assertThat(r.http()).as("开通租户: " + r.json()).isEqualTo(200);
        return new Tenant(r.body().path("orgId").asLong(), name,
                String.valueOf(body.get("code")), phone, r.body().path("tempPassword").asText());
    }

    @Test
    void overviewReturnsStatsAndWarnings() throws Exception {
        createTenant("ov", 10);
        String plat = login(PLATFORM, PLATFORM_PWD);
        Resp r = call(get("/api/platform/overview"), plat, null);
        assertThat(r.http()).isEqualTo(200);
        JsonNode body = r.body();
        assertThat(body.path("stats").path("tenants").asInt()).isPositive();
        assertThat(body.path("stats").has("expiring30")).isTrue();
        assertThat(body.path("stats").has("seatUsed")).isTrue();
        assertThat(body.path("expiring").isArray()).isTrue();
        assertThat(body.path("expiring").size()).isPositive();
        assertThat(body.path("recent").isArray()).isTrue();
        assertThat(body.path("recent").size()).isPositive();
        assertThat(body.path("recent").get(0).path("actionLabel").asText()).isNotBlank();
    }

    @Test
    void tenantExpiringSoonAppearsInWarningListAndAudit() throws Exception {
        Tenant t = createTenant("exp", 5);
        String plat = login(PLATFORM, PLATFORM_PWD);

        JsonNode overview = call(get("/api/platform/overview"), plat, null).body();
        JsonNode hit = null;
        for (JsonNode row : overview.path("expiring")) {
            if (row.path("orgId").asLong() == t.orgId()) hit = row;
        }
        assertThat(hit).as("新租户应出现在到期预警列表").isNotNull();
        assertThat(hit.path("remainDays").asLong()).isBetween(0L, 5L);

        // 开通动作写入平台审计，可按租户编码检索
        JsonNode audit = call(get("/api/platform/audit?keyword=" + t.code()), plat, null).body();
        assertThat(audit.path("total").asLong()).isPositive();
        JsonNode row = audit.path("rows").get(0);
        assertThat(row.path("action").asText()).isEqualTo("create");
        assertThat(row.path("actionLabel").asText()).isEqualTo("开通");
        assertThat(row.path("targetName").asText()).isEqualTo(t.name());
    }

    @Test
    void tenantAdminCannotOpenPlatformOverview() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        assertThat(call(get("/api/platform/overview"), admin, null).http()).isEqualTo(403);
    }

    @Test
    void meCarriesLicenseReminder() throws Exception {
        Tenant t = createTenant("me", 7);
        String token = login(t.phone(), t.tempPassword());

        JsonNode user = call(get("/api/auth/me"), token, null).body().path("user");
        assertThat(user.path("licenseEndAt").asText()).isEqualTo(LocalDate.now().plusDays(7).toString());
        assertThat(user.path("licenseRemainDays").asLong()).isEqualTo(7L);
        assertThat(user.path("licenseStatus").asText()).isEqualTo("active");
        assertThat(user.path("plan").asText()).isEqualTo("basic");
    }
}
