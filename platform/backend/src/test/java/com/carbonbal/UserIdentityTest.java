package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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
        assertThat(created.http()).as("创建成员响应: " + created.body()).isEqualTo(200);
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
    void importTemplateAndChineseRoleImport() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);

        byte[] tpl = mvc.perform(get("/api/users/import-template").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(tpl, StandardCharsets.UTF_8);
        assertThat(text).as("模板表头含用户类型列，示例行含普通用户/运营")
                .contains("手机号,姓名,用户类型,角色,工号,职位")
                .contains("普通用户").contains("运营");

        String filler = login("13800000002", "Test@2026!Pass");
        assertThat(status(get("/api/users/import-template"), filler))
                .as("普通用户不能下载导入模板").isEqualTo(403);

        String phone = "1397002" + String.format("%04d", System.currentTimeMillis() % 10000);
        MockMultipartFile file = new MockMultipartFile("file", "users.csv", "text/csv",
                ("手机号,姓名,用户类型,角色,工号,职位\n" + phone + ",导入测试,普通用户,运营,EMP9,工程师\n")
                        .getBytes(StandardCharsets.UTF_8));
        Resp r = call(multipart("/api/users/import").file(file), admin, null);
        assertThat(r.http()).as("导入响应: " + r.body()).isEqualTo(200);
        assertThat(r.body().path("success").asInt()).isEqualTo(1);
        assertThat(r.body().path("failed").asInt()).isZero();

        // ④ 用户类型留空 + 角色填「运营」→ 创建为普通用户且 roleId 指向运营角色
        String seed = String.format("%03d", System.currentTimeMillis() % 1000);
        String blankTypePhone = "1397003" + seed + "1";
        MockMultipartFile blankType = new MockMultipartFile("file", "users2.csv", "text/csv",
                ("手机号,姓名,用户类型,角色,工号,职位\n" + blankTypePhone + ",留空类型,,运营,EMP10,工程师\n")
                        .getBytes(StandardCharsets.UTF_8));
        Resp r2 = call(multipart("/api/users/import").file(blankType), admin, null);
        assertThat(r2.http()).as("留空用户类型导入响应: " + r2.body()).isEqualTo(200);
        assertThat(r2.body().path("success").asInt()).isEqualTo(1);
        assertThat(r2.body().path("failed").asInt()).isZero();
        JsonNode imported = findMemberByName(admin, "留空类型");
        assertThat(imported.path("userType").asText()).as("留空用户类型按普通用户").isEqualTo("user");
        assertThat(imported.path("roleName").asText()).as("角色列按名称匹配到运营").isEqualTo("运营");
        assertThat(imported.path("roleId").asLong()).as("roleId 已落库").isPositive();

        // ① 导出表头含「用户类型」，管理员行的角色列为空
        byte[] exported = mvc.perform(get("/api/users/export").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(exported, StandardCharsets.UTF_8);
        assertThat(csv).as("导出表头").contains("手机号,姓名,用户类型,角色,状态,工号,职位,最近登录");
        String adminLine = "";
        for (String line : csv.split("\r?\n")) {
            if (line.startsWith(ADMIN_PHONE)) adminLine = line;
        }
        assertThat(adminLine).as("管理员导出行的用户类型/角色列: " + adminLine).contains(",租户管理员,,");

        removeImportedMembers(admin, "导入测试", "留空类型");
    }

    @Test
    void importRejectsAdminAliasAndUnknownRole() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        String seed = String.format("%03d", System.currentTimeMillis() % 1000);
        String adminAliasPhone = "1397006" + seed + "1";
        String unknownRolePhone = "1397006" + seed + "2";
        String okPhone = "1397006" + seed + "3";
        MockMultipartFile file = new MockMultipartFile("file", "users3.csv", "text/csv",
                ("手机号,姓名,用户类型,角色,工号,职位\n"
                        + adminAliasPhone + ",角色列填管理员,普通用户,租户管理员,,\n"
                        + unknownRolePhone + ",角色不存在,普通用户,财务审计,,\n"
                        + okPhone + ",正常行,普通用户,,,\n").getBytes(StandardCharsets.UTF_8));
        Resp r = call(multipart("/api/users/import").file(file), admin, null);
        assertThat(r.http()).as("导入响应: " + r.body()).isEqualTo(200);
        assertThat(r.body().path("success").asInt()).as("只有正常行成功").isEqualTo(1);
        assertThat(r.body().path("failed").asInt()).isEqualTo(2);
        JsonNode errors = r.body().path("errors");
        assertThat(errors.get(0).path("line").asInt()).isEqualTo(2);
        assertThat(errors.get(0).path("message").asText())
                .as("角色列填租户管理员必须报错并提示改填用户类型列")
                .contains("不存在").contains("用户类型");
        assertThat(errors.get(1).path("line").asInt()).isEqualTo(3);
        assertThat(errors.get(1).path("message").asText())
                .as("不存在的角色必须报错").contains("不存在").contains("角色管理");

        removeImportedMembers(admin, "正常行");
    }

    /**
     * 租户在「角色管理」里自建的、名字与用户类型同名（如「管理员」）的角色：
     * 只要配置了就应当可用，不能被类型别名预先拦掉。
     */
    @Test
    void importAcceptsCustomRoleNamedLikeUserType() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        Resp role = call(post("/api/roles"), admin, Map.of("name", "管理员", "pages", List.of("home")));
        assertThat(role.http()).as("创建自建角色响应: " + role.body()).isEqualTo(200);
        long roleId = role.body().path("roleId").asLong();
        assertThat(roleId).as("自建角色应返回 roleId").isPositive();

        String seed = String.format("%03d", System.currentTimeMillis() % 1000);
        String phone = "1397007" + seed + "1";
        MockMultipartFile file = new MockMultipartFile("file", "users4.csv", "text/csv",
                ("手机号,姓名,用户类型,角色,工号,职位\n"
                        + phone + ",自建同名角色,普通用户,管理员,EMP11,工程师\n").getBytes(StandardCharsets.UTF_8));
        Resp r = call(multipart("/api/users/import").file(file), admin, null);
        assertThat(r.http()).as("导入响应: " + r.body()).isEqualTo(200);
        assertThat(r.body().path("success").asInt()).as("自建同名角色应可用: " + r.body()).isEqualTo(1);
        assertThat(r.body().path("failed").asInt()).isZero();
        JsonNode imported = findMemberByName(admin, "自建同名角色");
        assertThat(imported.path("userType").asText()).isEqualTo("user");
        assertThat(imported.path("roleName").asText()).isEqualTo("管理员");
        assertThat(imported.path("roleId").asLong()).isEqualTo(roleId);

        removeImportedMembers(admin, "自建同名角色");
    }

    /**
     * 清理用例自己导入出来的成员（{@code remove} = {@code status='removed'}，立即释放席位）。
     * <p>
     * 必须清：测试库是<b>整个测试 JVM 共享</b>的具名 H2 内存库（{@code jdbc:h2:mem:carbonbal}，
     * {@code DB_CLOSE_DELAY=-1}），{@code @DirtiesContext} 只换 Spring 上下文、不换库；dev 种子租户
     * {@code seat_limit=20} 会被各测试类累计占用，导入用例只增不减时，后续用例建成员会撞上限
     * 直接返回 409 SEAT_LIMIT（与业务代码无关，属测试数据污染）。
     *
     * @param token         租户管理员令牌
     * @param displayNames  本次导入创建的成员姓名
     */
    private void removeImportedMembers(String token, String... displayNames) throws Exception {
        for (String displayName : displayNames) {
            long membershipId = findMemberByName(token, displayName).path("membershipId").asLong();
            Resp r = call(delete("/api/users/" + membershipId), token, null);
            assertThat(r.http()).as("清理导入成员「" + displayName + "」响应: " + r.body()).isEqualTo(200);
        }
    }

    /** 按姓名在成员列表里找到该行（列表接口返回脱敏手机号，用姓名定位最稳）。 */
    private JsonNode findMemberByName(String token, String displayName) throws Exception {
        JsonNode list = call(get("/api/users"), token, null).body();
        JsonNode hit = null;
        for (JsonNode n : list) {
            if (displayName.equals(n.path("displayName").asText())) hit = n;
        }
        assertThat(hit).as("成员列表中应存在: " + displayName).isNotNull();
        return hit;
    }

    @Test
    void rebindRequiresBothCodes() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);
        Resp r = call(post("/api/auth/phone/rebind"), admin, Map.of(
                "oldCode", "000000", "newPhone", "13970059999", "newCode", "000000"));
        assertThat(r.http()).as("无有效验证码不能换绑").isEqualTo(400);
    }
}
