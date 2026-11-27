package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集团汇总的数据范围（P1-1）：页面门禁 + 成员关系双层校验。
 * <p>
 * 三条期望行为：
 * <ol>
 *   <li>默认角色成员（没有 {@code group.overview}）→ 403 {@code PAGE_FORBIDDEN}；</li>
 *   <li>被显式授予 {@code group.overview} 但不是集团管理员 → 200，且只含本人企业，<b>不含</b>兄弟工厂；</li>
 *   <li>集团父企业的管理员 → 200，且包含父企业与下属工厂。</li>
 * </ol>
 */
class GroupScopeTest extends ApiTestSupport {

    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    /** 一个集团：父企业 + 两家工厂。 */
    private record FactoryGroup(long parentId, long child1Id, long child2Id,
                                String parentPhone, String parentTemp,
                                String child1Phone, String child1Temp) {}

    private record Tenant(long orgId, String phone, String tempPassword) {}

    private Tenant newTenant(String plat, String name, Long parentOrgId) throws Exception {
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        String phone = "138" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name + " " + sfx);
        body.put("code", "GRP-" + sfx + "-" + Math.abs(name.hashCode() % 1000));
        body.put("adminPhone", phone);
        body.put("adminName", name + "管理员");
        body.put("plan", "basic");
        body.put("seatLimit", 10);
        if (parentOrgId != null) body.put("parentOrgId", parentOrgId);
        Resp r = call(post("/api/platform/tenants"), plat, body);
        assertThat(r.http()).as("开通企业 " + name + ": " + r.json()).isEqualTo(200);
        return new Tenant(r.body().path("orgId").asLong(), phone, r.body().path("tempPassword").asText());
    }

    private FactoryGroup createGroup() throws Exception {
        String plat = login(PLATFORM, PLATFORM_PWD);
        Tenant parent = newTenant(plat, "TST 集团总部", null);
        Tenant child1 = newTenant(plat, "TST 一号工厂", parent.orgId());
        Tenant child2 = newTenant(plat, "TST 二号工厂", parent.orgId());
        return new FactoryGroup(parent.orgId(), child1.orgId(), child2.orgId(),
                parent.phone(), parent.tempPassword(), child1.phone(), child1.tempPassword());
    }

    /** 首次登录必须先改初始密码，否则业务接口会被 403 MUST_CHANGE_PASSWORD 拦住。 */
    private String activate(String phone, String tempPassword) throws Exception {
        String token = login(phone, tempPassword);
        if (!STRONG.equals(tempPassword)) {
            assertThat(call(post("/api/auth/password/change"), token, Map.of(
                    "oldPassword", tempPassword, "newPassword", STRONG)).http()).isEqualTo(200);
        }
        return login(phone, STRONG);
    }

    /** 集团父企业的管理员登录令牌。 */
    private String groupAdmin(FactoryGroup g) throws Exception {
        return activate(g.parentPhone(), g.parentTemp());
    }

    @Test
    void defaultRoleMemberIsForbidden() throws Exception {
        FactoryGroup g = createGroup();
        String parentAdmin = groupAdmin(g);

        // 取该租户的默认角色（【运营】），用它建一个普通成员
        JsonNode roles = call(get("/api/roles"), parentAdmin, null).body();
        long defaultRoleId = 0;
        for (JsonNode row : roles) {
            if (row.path("defaultRole").asBoolean()) defaultRoleId = row.path("roleId").asLong();
        }
        assertThat(defaultRoleId).as("租户应有默认角色").isPositive();

        String phone = "135" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        Resp created = call(post("/api/users"), parentAdmin, Map.of(
                "phone", phone, "displayName", "普通成员", "userType", "user", "roleId", defaultRoleId));
        assertThat(created.http()).as("建普通成员: " + created.json()).isEqualTo(200);
        String member = activate(phone, created.body().path("tempPassword").asText());

        Resp r = call(get("/api/group/summary"), member, null);
        assertThat(r.http()).as("默认角色成员不得进入集团汇总").isEqualTo(403);
        assertThat(r.code()).isEqualTo("PAGE_FORBIDDEN");

        // 反向对照：集团管理员可以进入
        assertThat(call(get("/api/group/summary"), parentAdmin, null).http()).isEqualTo(200);
    }

    @Test
    void grantedPageWithoutGroupAdminSeesOwnOrgOnly() throws Exception {
        FactoryGroup g = createGroup();
        String parentAdmin = groupAdmin(g);

        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), parentAdmin, Map.of(
                "name", "集团只读" + sfx, "pages", List.of("group.overview")));
        assertThat(role.http()).as("建集团只读角色: " + role.json()).isEqualTo(200);

        String phone = "1372" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), parentAdmin, Map.of(
                "phone", phone, "displayName", "集团只读" + sfx,
                "userType", "user", "roleId", role.body().path("roleId").asLong()));
        assertThat(created.http()).as("建集团只读用户: " + created.json()).isEqualTo(200);
        String token = activate(phone, created.body().path("tempPassword").asText());

        Resp r = call(get("/api/group/summary"), token, null);
        assertThat(r.http()).as("被授予页面即可访问").isEqualTo(200);
        JsonNode body = r.body();
        assertThat(body.path("orgCount").asInt()).as("非集团管理员只应看到本企业").isEqualTo(1);
        List<Long> ids = orgIds(body);
        assertThat(ids).containsExactly(g.parentId());
        assertThat(ids).as("绝不返回兄弟工厂").doesNotContain(g.child1Id(), g.child2Id());
    }

    @Test
    void groupAdminSeesOwnFactoriesOnly() throws Exception {
        FactoryGroup g = createGroup();
        String parentAdmin = groupAdmin(g);

        Resp r = call(get("/api/group/summary"), parentAdmin, null);
        assertThat(r.http()).isEqualTo(200);
        List<Long> ids = orgIds(r.body());
        assertThat(ids).as("集团管理员应看到父企业与两家工厂")
                .contains(g.parentId(), g.child1Id(), g.child2Id());

        // 兄弟工厂（非集团管理员）不应看到同集团其它工厂
        String childAdmin = activate(g.child1Phone(), g.child1Temp());
        Resp child = call(get("/api/group/summary"), childAdmin, null);
        assertThat(child.http()).isEqualTo(200);
        List<Long> childIds = orgIds(child.body());
        assertThat(childIds).as("工厂管理员只应看到自己").containsExactly(g.child1Id());
        assertThat(childIds).doesNotContain(g.child2Id(), g.parentId());
    }

    @Test
    void summaryIsScopedToAuthorizedOrgs() throws Exception {
        FactoryGroup g = createGroup();
        String parentAdmin = groupAdmin(g);
        Resp r = call(get("/api/group/summary?year=2026"), parentAdmin, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("multiOrg").asBoolean()).as("多企业时应标记 multiOrg").isTrue();
        for (JsonNode row : r.body().path("orgs")) {
            long id = row.path("orgId").asLong();
            assertThat(List.of(g.parentId(), g.child1Id(), g.child2Id()))
                    .as("不得返回未授权企业: " + id).contains(id);
        }
    }

    private static List<Long> orgIds(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : body.path("orgs")) ids.add(row.path("orgId").asLong());
        return ids;
    }
}
