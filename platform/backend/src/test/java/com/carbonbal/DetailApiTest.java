package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 主数据详情接口：存在的返回 200，不存在 / 跨企业统一 404（不泄露存在性）。
 * 原先这些 GET /{id} 没有实现，会落到 405，客户端拿不到「没有这条」的语义。
 */
class DetailApiTest extends ApiTestSupport {

    private static final String ADMIN = "Admin@2026!Pass";
    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    @Autowired
    JdbcTemplate jdbc;

    private long idOf(String table) {
        Long id = jdbc.queryForObject(
                "select min(id) from " + table + " where org_id = 1 and disabled = 0", Long.class);
        assertThat(id).as(table + " 没有可用样本").isNotNull();
        return id;
    }

    @Test
    void detailEndpointsReturnRecordOr404() throws Exception {
        String admin = login("admin", ADMIN);
        String[][] cases = {
                {"/api/products/", "product"},
                {"/api/suppliers/", "supplier"},
                {"/api/devices/", "device"},
                {"/api/quota-years/", "quota_year"},
                {"/api/carbon-assets/", "carbon_asset"},
                {"/api/carbon-trades/", "carbon_trade"},
                {"/api/footprints/", "product_footprint"},
                {"/api/alert-rules/", "alert_rule"},
                {"/api/energy-mgmt/", "energy_mgmt"},
                {"/api/retrofits/", "retrofit"},
        };
        for (String[] c : cases) {
            long id = idOf(c[1]);
            Resp ok = call(get(c[0] + id), admin, null);
            assertThat(ok.http()).as(c[0] + id + " 应返回详情").isEqualTo(200);
            assertThat(ok.body().path("id").asLong()).isEqualTo(id);

            Resp missing = call(get(c[0] + "9999999"), admin, null);
            assertThat(missing.http()).as(c[0] + " 不存在应 404").isEqualTo(404);
            assertThat(missing.code()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void detailDoesNotLeakCrossTenantRecord() throws Exception {
        // B 企业建一条产品
        String plat = login(PLATFORM, PLATFORM_PWD);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String phone = "1389" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "详情隔离租户 " + sfx);
        body.put("code", "DTL-" + sfx);
        body.put("adminPhone", phone);
        body.put("adminName", "详情管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 3);
        Resp tenant = call(post("/api/platform/tenants"), plat, body);
        assertThat(tenant.http()).as("开通租户: " + tenant.json()).isEqualTo(200);

        String temp = tenant.body().path("tempPassword").asText();
        String adminB = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), adminB, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        adminB = login(phone, STRONG);
        Resp created = call(post("/api/products"), adminB, Map.of(
                "name", "B 的产品" + sfx, "code", "B-P-" + sfx,
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(created.http()).isEqualTo(200);
        long pid = created.body().path("id").asLong();

        // A 企业按 id 取详情：应 404，而不是 200/403
        String adminA = login("admin", ADMIN);
        for (String path : new String[]{"/api/products/" + pid}) {
            Resp r = call(get(path), adminA, null);
            assertThat(r.http()).as(path + " 跨企业应 404").isEqualTo(404);
            assertThat(r.code()).isEqualTo("NOT_FOUND");
        }
        // B 自己可以取到
        JsonNode own = call(get("/api/products/" + pid), adminB, null).body();
        assertThat(own.path("name").asText()).isEqualTo("B 的产品" + sfx);
    }
}
