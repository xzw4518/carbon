package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 页面权限对“统一入口”的覆盖：导出、导入、附件、因子、任务清单都不能绕过页面集合。
 * 这些入口不按 URI 前缀区分模块，必须在接口内部按 resource 逐项校验。
 */
class PageGateTest extends ApiTestSupport {

    private static final String ADMIN = "Admin@2026!Pass";
    private static final String STRONG = "Abcdef123456!";

    /** 只拥有 product.list 的普通用户令牌。 */
    private String limitedToken() throws Exception {
        String admin = login("admin", ADMIN);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin, Map.of(
                "name", "门禁只读" + sfx, "pages", List.of("product.list")));
        assertThat(role.http()).as("建角色: " + role.json()).isEqualTo(200);
        String phone = "1372" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "门禁用户" + sfx,
                "userType", "user", "roleId", role.body().path("roleId").asLong()));
        assertThat(created.http()).as("建用户: " + created.json()).isEqualTo(200);
        String temp = created.body().path("tempPassword").asText();
        String token = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), token, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        return login(phone, STRONG);
    }

    @Test
    void exportIsGatedByResource() throws Exception {
        String token = limitedToken();
        assertThat(status(get("/api/export?resource=products"), token))
                .as("有产品页面可导出产品").isEqualTo(200);
        for (String res : List.of("evidences", "carbon-trades", "alert-records", "devices", "readings")) {
            Resp r = call(get("/api/export?resource=" + res), token, null);
            assertThat(r.http()).as("无页面不应导出 " + res).isEqualTo(403);
            assertThat(r.code()).isEqualTo("PAGE_FORBIDDEN");
        }
    }

    @Test
    void importAndTemplateAreGatedByResource() throws Exception {
        String token = limitedToken();
        assertThat(status(get("/api/import/template?resource=products"), token))
                .as("有产品页面可下产品模板").isEqualTo(200);
        Resp tpl = call(get("/api/import/template?resource=suppliers"), token, null);
        assertThat(tpl.http()).as("无供应商页面不可下模板").isEqualTo(403);
        assertThat(tpl.code()).isEqualTo("PAGE_FORBIDDEN");
    }

    @Test
    void filesAreGatedByEvidenceOrReportPage() throws Exception {
        String token = limitedToken();
        Resp list = call(get("/api/files"), token, null);
        assertThat(list.http()).as("无核查页面不可列附件").isEqualTo(403);
        assertThat(list.code()).isEqualTo("PAGE_FORBIDDEN");
        assertThat(call(get("/api/files/1/download"), token, null).http())
                .as("无核查页面不可下载附件").isEqualTo(403);

        // 有原始凭证页面即可用附件（正向用例，避免误伤）
        String admin = login("admin", ADMIN);
        String sfx = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin, Map.of("name", "凭证角色" + sfx, "pages", List.of("evidence.list")));
        String phone = "1375" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), admin, Map.of(
                "phone", phone, "displayName", "凭证用户" + sfx,
                "userType", "user", "roleId", role.body().path("roleId").asLong()));
        String temp = created.body().path("tempPassword").asText();
        String t2 = login(phone, temp);
        assertThat(call(post("/api/auth/password/change"), t2, Map.of(
                "oldPassword", temp, "newPassword", STRONG)).http()).isEqualTo(200);
        t2 = login(phone, STRONG);
        assertThat(call(get("/api/files"), t2, null).http()).as("有凭证页面可用附件").isEqualTo(200);
        assertThat(status(get("/api/export?resource=evidences"), t2)).as("可导出原始凭证").isEqualTo(200);
    }

    @Test
    void factorsAndJobsAreNotPublicToBusinessUsers() throws Exception {
        String token = limitedToken();
        assertThat(call(get("/api/emission-factors"), token, null).http())
                .as("因子属于核算页面").isEqualTo(403);
        assertThat(call(get("/api/jobs"), token, null).http())
                .as("任务清单只给管理员").isEqualTo(403);

        String admin = login("admin", ADMIN);
        assertThat(call(get("/api/emission-factors"), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/jobs"), admin, null).http()).isEqualTo(200);
        assertThat(call(get("/api/files"), admin, null).http()).isEqualTo(200);
        assertThat(status(get("/api/export?resource=evidences"), admin)).isEqualTo(200);
    }

    @Test
    void badInputIsClientErrorNotServerError() throws Exception {
        String admin = login("admin", ADMIN);

        Resp type = call(get("/api/products?page=abc"), admin, null);
        assertThat(type.http()).as("分页参数类型错误应为 400").isEqualTo(400);
        assertThat(type.code()).isEqualTo("VALIDATION");

        Resp dateType = call(get("/api/alert-records?from=abc&to=xyz"), admin, null);
        assertThat(dateType.http()).as("日期格式错误应为 400").isEqualTo(400);

        Resp missing = call(get("/api/export"), admin, null);
        assertThat(missing.http()).as("缺少 resource 应为 400").isEqualTo(400);
        assertThat(missing.code()).isEqualTo("VALIDATION");

        // 详情接口已实现：GET 有记录返回 200
        assertThat(call(get("/api/products/1"), admin, null).http()).as("详情 200").isEqualTo(200);
        // 仍不支持的方法：POST 详情路径应为 405 而不是 500
        Resp method = call(post("/api/products/1"), admin, Map.of("name", "x"));
        assertThat(method.http()).as("不支持的方法应为 405").isEqualTo(405);
        assertThat(method.code()).isEqualTo("METHOD_NOT_ALLOWED");

        // 上传接口收到 JSON 而不是 multipart：应提示缺文件字段，而不是 500
        Resp noFile = call(post("/api/files"), admin, Map.of("file", "x"));
        assertThat(noFile.http()).as("缺文件字段应为 400").isEqualTo(400);
        assertThat(noFile.code()).isEqualTo("VALIDATION");

        Resp badBody = call(put("/api/products/1"), admin, "not-an-object");
        assertThat(badBody.http()).as("请求体不可解析应为 400").isEqualTo(400);
    }
}
