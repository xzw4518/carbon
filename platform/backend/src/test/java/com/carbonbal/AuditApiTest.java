package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 审计日志：只读、按租户隔离、仅租户管理员可查；
 * 平台操作写入 org_id=0，不混进任何租户的审计列表。
 */
class AuditApiTest extends ApiTestSupport {

    private static final String ADMIN = "Admin@2026!Pass";
    private static final String PLATFORM = "13900000000";
    private static final String PLATFORM_PWD = "Superadmin@2026!Pass";

    /** 审计由业务写入触发：先造一条产品新增记录，保证有数据可查。 */
    private void writeOneAudit(String admin) throws Exception {
        String ts = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp r = call(post("/api/products"), admin, Map.of(
                "name", "审计用例产品" + ts, "code", "AUD-" + ts,
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(r.http()).as("造审计数据: " + r.json()).isEqualTo(200);
    }

    @Test
    void tenantAdminReadsPagedAudit() throws Exception {
        String admin = login("admin", ADMIN);
        writeOneAudit(admin);
        Resp r = call(get("/api/audit-logs?page=1&size=5"), admin, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("page").asInt()).isEqualTo(1);
        assertThat(r.body().path("size").asInt()).isEqualTo(5);
        assertThat(r.body().path("total").asLong()).isPositive();
        JsonNode rows = r.body().path("rows");
        assertThat(rows.size()).isBetween(1, 5);
        JsonNode first = rows.get(0);
        assertThat(first.path("entity").asText()).isNotBlank();
        assertThat(first.path("actionLabel").asText()).isNotBlank();
        assertThat(first.path("loggedAt").asText()).isNotBlank();
    }

    @Test
    void normalUserCannotReadAudit() throws Exception {
        // 演示账号 filler 是普通用户（运营角色），首次登录不强制改密
        String filler = login("13800000002", "Test@2026!Pass");
        Resp r = call(get("/api/audit-logs"), filler, null);
        assertThat(r.http()).isEqualTo(403);
    }

    @Test
    void filtersNarrowTheResult() throws Exception {
        String admin = login("admin", ADMIN);
        writeOneAudit(admin);

        JsonNode byEntity = call(get("/api/audit-logs?entity=product&size=20"), admin, null).body();
        assertThat(byEntity.path("total").asLong()).isPositive();
        for (JsonNode row : byEntity.path("rows")) {
            assertThat(row.path("entity").asText()).isEqualTo("product");
        }

        JsonNode byAction = call(get("/api/audit-logs?action=create&size=20"), admin, null).body();
        assertThat(byAction.path("total").asLong()).isPositive();
        for (JsonNode row : byAction.path("rows")) {
            assertThat(row.path("action").asText()).isEqualTo("create");
            assertThat(row.path("actionLabel").asText()).isEqualTo("新增");
        }

        JsonNode future = call(get("/api/audit-logs?from=2999-01-01"), admin, null).body();
        assertThat(future.path("total").asLong()).isZero();
    }

    @Test
    void facetsListModulesAndActions() throws Exception {
        String admin = login("admin", ADMIN);
        writeOneAudit(admin);
        JsonNode body = call(get("/api/audit-logs/facets"), admin, null).body();
        assertThat(body.path("entities").size()).isPositive();
        assertThat(body.path("actions").size()).isPositive();
        boolean hasProduct = false;
        for (JsonNode e : body.path("entities")) {
            if ("product".equals(e.path("value").asText())) {
                hasProduct = true;
                assertThat(e.path("label").asText()).isEqualTo("产品");
            }
        }
        assertThat(hasProduct).isTrue();
    }

    @Test
    void platformAuditIsSeparateFromTenantAudit() throws Exception {
        String plat = login(PLATFORM, PLATFORM_PWD);
        String ts = String.valueOf(System.currentTimeMillis() % 100000);
        String phone = "1393000" + String.format("%04d", System.currentTimeMillis() % 10000);
        Resp created = call(post("/api/platform/tenants"), plat, Map.of(
                "name", "审计隔离租户 " + ts, "code", "AUD-T-" + ts,
                "adminPhone", phone, "adminName", "隔离管理员",
                "plan", "basic", "seatLimit", 3));
        assertThat(created.http()).as("开通租户: " + created.json()).isEqualTo(200);

        JsonNode p = call(get("/api/platform/audit?size=50"), plat, null).body();
        assertThat(p.path("total").asLong()).isPositive();
        String tenantName = "审计隔离租户 " + ts;
        boolean sawCreate = false;
        boolean sawOwnRowResolved = false;
        for (JsonNode row : p.path("rows")) {
            assertThat(row.path("entity").asText()).isEqualTo("tenant");
            if (!"create".equals(row.path("action").asText())) continue;
            sawCreate = true;
            assertThat(row.path("actionLabel").asText()).isEqualTo("开通");
            // targetName 由 org 表反查；历史 create 行对应的租户可能已被其它用例清理，
            // 名字解析不到属正常（不是脏数据）。因此只断言"本次开通的这一行"能解析出名字。
            if (tenantName.equals(row.path("targetName").asText())) sawOwnRowResolved = true;
        }
        assertThat(sawCreate).isTrue();
        assertThat(sawOwnRowResolved).as("本次开通的租户应能以 targetName 解析出名称").isTrue();

        // 租户管理员的审计列表里不出现平台操作（org_id=0 隔离）
        long platformRowId = p.path("rows").get(0).path("id").asLong();
        String admin = login("admin", ADMIN);
        JsonNode t = call(get("/api/audit-logs?size=100"), admin, null).body();
        for (JsonNode row : t.path("rows")) {
            assertThat(row.path("id").asLong()).isNotEqualTo(platformRowId);
        }

        // 租户管理员不能进平台审计
        assertThat(call(get("/api/platform/audit"), admin, null).http()).isEqualTo(403);
    }
}
