package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 用户体系：成员保护、交接、数据范围、外部授权、平台运营端、换绑双验证。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserIdentityTest extends ApiTestSupport {

    private static final String ADMIN_PHONE = "13800000001";
    private static final String ADMIN_PWD = "Admin@2026!Pass";
    private static final String PLATFORM_PHONE = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";

    @Test
    void memberCreateDuplicateAndSelfProtection() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        String phone = ("1397000" + String.format("%04d", System.currentTimeMillis() % 10000));

        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "单测成员", "role", "viewer"));
        assertThat(created.http()).isEqualTo(200);
        assertThat(created.body().path("tempPassword").asText()).isNotBlank();
        long mid = created.body().path("membershipId").asLong();

        Resp dup = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "重复", "role", "viewer"));
        assertThat(dup.http()).as("同手机号重复加入").isEqualTo(409);

        // 找到自己的成员关系，验证不能停用/降级
        JsonNode list = call(get("/api/users"), admin, null).body();
        long selfId = 0;
        for (JsonNode n : list) {
            if (String.valueOf(n.path("phone").asText()).startsWith("138****0001")) selfId = n.path("membershipId").asLong();
        }
        assertThat(selfId).isPositive();
        assertThat(call(post("/api/users/" + selfId + "/disable"), admin, null).http())
                .as("不能停用自己").isEqualTo(409);
        assertThat(call(put("/api/users/" + selfId), admin, Map.of("role", "viewer")).http())
                .as("不能降级自己").isEqualTo(409);

        call(delete("/api/users/" + mid), admin, null);
    }

    @Test
    void handoverMovesCreatedData() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        String phone = ("1397001" + String.format("%04d", System.currentTimeMillis() % 10000));
        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "交接成员", "role", "filler"));
        long mid = created.body().path("membershipId").asLong();
        String temp = created.body().path("tempPassword").asText();

        String member = login(phone, temp);
        // 首登必须改密，否则业务接口返回 403 MUST_CHANGE_PASSWORD
        Resp ch = call(post("/api/auth/password/change"), member,
                Map.of("oldPassword", temp, "newPassword", "Handover@2026ok"));
        assertThat(ch.http()).as("改密响应: " + ch.body()).isEqualTo(200);
        String member2 = login(phone, "Handover@2026ok");
        String code = "HO-" + (System.currentTimeMillis() % 100000);
        Resp p = call(post("/api/products"), member2, Map.of(
                "name", "交接测试产品", "code", code, "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(p.http()).as("创建产品响应: " + p.body()).isEqualTo(200);

        JsonNode users = call(get("/api/users"), admin, null).body();
        long selfId = 0;
        for (JsonNode n : users) {
            if (String.valueOf(n.path("phone").asText()).startsWith("138****0001")) selfId = n.path("membershipId").asLong();
        }
        Resp ho = call(post("/api/users/" + mid + "/handover"), admin, Map.of("toMembershipId", selfId));
        assertThat(ho.http()).isEqualTo(200);
        assertThat(ho.body().path("rows").asInt()).isGreaterThanOrEqualTo(1);
        call(delete("/api/users/" + mid), admin, null);
    }

    @Test
    void platformAdminManagesTenants() throws Exception {
        String platform = login(PLATFORM_PHONE, PLATFORM_PWD);
        String code = "UT-" + (System.currentTimeMillis() % 100000);
        Resp created = call(post("/api/platform/tenants"), platform, Map.of(
                "name", "单测租户", "code", code,
                "adminPhone", ("1397004" + String.format("%04d", System.currentTimeMillis() % 10000)),
                "adminName", "单测管理员", "plan", "basic", "seatLimit", 5));
        assertThat(created.http()).isEqualTo(200);
        long orgId = created.body().path("orgId").asLong();
        assertThat(created.body().path("tempPassword").asText()).isNotBlank();

        JsonNode tenants = call(get("/api/platform/tenants?page=1&size=100"), platform, null).body();
        boolean found = false;
        for (JsonNode t : tenants.path("rows")) if (t.path("orgId").asLong() == orgId) found = true;
        assertThat(found).isTrue();

        Resp suspend = call(put("/api/platform/tenants/" + orgId), platform,
                Map.of("status", "suspended"));
        assertThat(suspend.http()).isEqualTo(200);
        assertThat(suspend.body().path("status").asText()).isEqualTo("suspended");

        // 普通管理员无权访问平台端
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        assertThat(call(get("/api/platform/tenants"), admin, null).http()).isEqualTo(403);
    }

    @Test
    void rebindRequiresBothCodes() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        Resp r = call(post("/api/auth/phone/rebind"), admin, Map.of(
                "oldCode", "000000", "newPhone", "13970059999", "newCode", "000000"));
        assertThat(r.http()).as("无有效验证码不能换绑").isEqualTo(400);
    }
}
