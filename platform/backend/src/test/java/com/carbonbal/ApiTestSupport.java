package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 接口测试基类：登录/验证码/请求封装，以及 {@link AfterAll} 的测试数据清理。
 * <p>
 * 清理是本类的核心职责（P0-1 / P1-10）：测试与演示库同实例，若不清理，每跑一次 {@code mvn test}
 * 平台租户列表就会多出几个「TST 功能测试租户」，租户数/账号数随之虚高。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li><b>只允许在 H2 内存库运行</b>：清理会删除 {@code org_id <> 1} 的全部数据，
 *       非 H2 环境直接 {@link IllegalStateException}（fail-fast，不用 {@code assumeTrue} 静默跳过）；</li>
 *   <li><b>按外键顺序单表 DELETE</b>：H2（MODE=MySQL）不支持 MySQL 的多表 {@code DELETE ... JOIN}，
 *       历史实现在 H2 下整段静默失效，MySQL 上也只是"恰好没有脏数据"才不报错；</li>
 *   <li><b>失败必须上抛</b>：逐条收集失败、汇总抛出，并断言无残留，绝不再 {@code catch} 吞掉。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ApiTestSupport {

    /** 演示库共用的租户 ID；测试新建的租户在用例结束后统一清理。 */
    protected static final long DEMO_ORG_ID = 1L;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    private DataSource dataSource;

    /** 逃生阀：仅在"已确认是独立测试库"时置 true；默认 false，非 H2 直接拒绝运行。 */
    @Value("${app.test.allow-non-h2:false}")
    private boolean allowNonH2;

    private String cachedProduct;

    /* ==================== 环境守卫 ==================== */

    /**
     * 环境守卫：清理语句只对 H2 内存库安全。
     *
     * @throws IllegalStateException 当前数据库不是 H2 且未显式放行
     */
    @BeforeAll
    void guardH2Only() {
        String product = databaseProduct();
        if ("H2".equalsIgnoreCase(product)) return;
        if (allowNonH2) {
            System.err.println("[WARN] app.test.allow-non-h2=true：测试清理会删除 org_id<>1 的全部数据，"
                    + "当前数据库=" + product);
            return;
        }
        throw new IllegalStateException(
                "测试清理会删除 org_id<>1 的全部数据，只允许在 H2 内存库运行；当前数据库=" + product
                        + "。请使用 H2（默认 dev profile）或独立测试库，并在确认后设置 app.test.allow-non-h2=true。");
    }

    /** 数据库产品名（一次性缓存）：判定环境不看 profile 字符串，直接问 JDBC。 */
    protected String databaseProduct() {
        if (cachedProduct != null) return cachedProduct;
        try (Connection c = dataSource.getConnection()) {
            cachedProduct = c.getMetaData().getDatabaseProductName();
            return cachedProduct;
        } catch (SQLException e) {
            throw new IllegalStateException("无法获取数据库元数据", e);
        }
    }

    /* ==================== 请求封装 ==================== */

    protected record Resp(int http, JsonNode json) {
        public Object status() {
            return json == null ? null : (json.path("status").isNumber() ? json.path("status").asInt() : json.path("status").asText());
        }
        public String code() {
            return json == null ? null : json.path("status").asText();
        }
        public JsonNode body() {
            return json == null ? null : json.path("body");
        }
    }

    /**
     * 取一张图形验证码，并从 SVG 中解析出字符。
     * 密码登录已强制验证码，测试必须真实走一遍，而不是绕过校验。
     */
    protected String[] captcha() throws Exception {
        Resp r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/captcha"), null, null);
        String id = r.body().path("captchaId").asText();
        String img = r.body().path("image").asText();
        String svg = new String(java.util.Base64.getDecoder().decode(img.substring(img.indexOf(',') + 1)),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<text[^>]*>([^<])</text>").matcher(svg);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append(m.group(1));
        return new String[]{id, sb.toString()};
    }

    protected String login(String user, String pwd) throws Exception {
        String[] cap = captcha();
        Resp r = call(post("/api/auth/login"), null, java.util.Map.of(
                "username", user, "password", pwd, "captchaId", cap[0], "captchaCode", cap[1]));
        if (r.http() != 200) throw new IllegalStateException("login failed: " + user);
        return r.body().path("token").asText();
    }

    /**
     * 带图形验证码的登录尝试，返回原始响应。
     * 供锁定、停用、密码错误等需要断言失败结果的用例使用。
     */
    protected Resp attemptLogin(String account, String pwd, boolean byUsername) throws Exception {
        String[] cap = captcha();
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put(byUsername ? "username" : "phone", account);
        body.put("password", pwd);
        body.put("captchaId", cap[0]);
        body.put("captchaCode", cap[1]);
        return call(post("/api/auth/login"), null, body);
    }

    /**
     * 建一个只能进入指定页面的普通用户并返回令牌，用于页面级越权用例。
     */
    protected String limitedUser(java.util.List<String> pages) throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        String suffix = String.valueOf(System.nanoTime() % 1_000_000L);
        Resp role = call(post("/api/roles"), admin,
                java.util.Map.of("name", "受限" + suffix, "pages", pages));
        if (role.http() != 200) throw new IllegalStateException("create role failed: " + role.json());
        long roleId = role.body().path("roleId").asLong();
        String phone = "1398" + String.format("%07d", System.nanoTime() % 10_000_000L);
        Resp created = call(post("/api/users"), admin, java.util.Map.of(
                "phone", phone, "displayName", "受限用户" + suffix,
                "userType", "user", "roleId", roleId));
        if (created.http() != 200) throw new IllegalStateException("create user failed: " + created.json());
        String temp = created.body().path("tempPassword").asText();
        return login(phone, temp);
    }

    protected Resp call(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(req).andReturn();
        String text = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode node = text == null || text.isBlank() ? null : json.readTree(text);
        return new Resp(res.getResponse().getStatus(), node);
    }

    /** 只关心状态码的场景：CSV/文件等非 JSON 响应不能走 {@link #call}。 */
    protected int status(MockHttpServletRequestBuilder req, String token) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    /* ==================== 清理 ==================== */

    /**
     * 清理测试自建的租户与账号。
     * <p>
     * 语句顺序按外键依赖排列（子表先删）；每类都做残留断言，任何一条失败都会汇总抛出，
     * 让"清理静默失效"这类问题在第一轮就暴露，而不是等 data-check 事后发现。
     */
    @AfterAll
    void cleanupTestTenants() {
        List<String> failures = new ArrayList<>();
        for (Stmt s : cleanupStatements()) {
            try {
                jdbc.update(s.sql(), s.params());
            } catch (DataAccessException e) {
                failures.add(s.sql() + " → " + rootMessage(e));
            }
        }
        // 验证码按时间清理：用 JDBC 参数传时间，避免 date_sub/now() 这类方言写法
        try {
            jdbc.update("delete from captcha where created_at < ?", LocalDateTime.now().minusHours(1));
        } catch (DataAccessException e) {
            failures.add("delete from captcha where created_at < ? → " + rootMessage(e));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("测试清理失败 " + failures.size() + " 条: " + String.join(" | ", failures));
        }
        assertNoResidue();
    }

    /** 一条清理语句 + 参数（参数个数必须与占位符一致，否则 H2 会报 parameterIndex 越界）。 */
    private record Stmt(String sql, Object[] params) {}

    private static Stmt st(String sql, Object... params) {
        return new Stmt(sql, params);
    }

    /** 按外键安全顺序排列的清理语句（全部为单表 DELETE，H2 与 MySQL 通用）。 */
    private List<Stmt> cleanupStatements() {
        long demo = DEMO_ORG_ID;
        return List.of(
                // 角色与页面（tenant_role_page 自带 org_id，可直接按租户删）
                st("delete from tenant_role_page where org_id <> ?", demo),
                st("delete from tenant_role where org_id <> ?", demo),
                // 设备读数 → 设备
                st("delete from device_reading where device_id in (select id from device where org_id <> ?)", demo),
                st("delete from device where org_id <> ?", demo),
                // 产品碳足迹 → 产品
                st("delete from product_footprint where product_id in (select id from product where org_id <> ?)", demo),
                st("delete from product where org_id <> ?", demo),
                // 交易 → 碳资产
                st("delete from carbon_trade where asset_id in (select id from carbon_asset where org_id <> ?)", demo),
                st("delete from carbon_asset where org_id <> ?", demo),
                // 核算明细/报告/解锁 → 核算期
                st("delete from accounting_line where period_id in "
                        + "(select id from accounting_period where org_id <> ?)", demo),
                st("delete from report where period_id in "
                        + "(select id from accounting_period where org_id <> ?)", demo),
                st("delete from period_unlock where period_id in "
                        + "(select id from accounting_period where org_id <> ?)", demo),
                st("delete from accounting_period where org_id <> ?", demo),
                // 其余按 org_id 归属的叶子表（无外键，先清子表再清 org）
                st("delete from quota_year where org_id <> ?", demo),
                st("delete from production_value where org_id <> ?", demo),
                st("delete from total_value where org_id <> ?", demo),
                st("delete from energy_mgmt where org_id <> ?", demo),
                st("delete from retrofit where org_id <> ?", demo),
                st("delete from alert_record where org_id <> ?", demo),
                st("delete from alert_event where org_id <> ?", demo),
                st("delete from alert_rule where org_id <> ?", demo),
                st("delete from evidence where org_id <> ?", demo),
                st("delete from third_party_report where org_id <> ?", demo),
                st("delete from supplier where org_id <> ?", demo),
                st("delete from facility where org_id <> ?", demo),
                st("delete from idempotency_record where org_id <> ?", demo),
                st("delete from login_log where org_id <> ?", demo),
                // 成员与账号（保留平台超管；platform_admin 为 BOOLEAN，用 FALSE 兼容 H2/MySQL）
                st("delete from user_tenant where org_id <> ?", demo),
                st("delete from app_user where platform_admin = FALSE "
                        + "and id not in (select user_id from user_tenant)"),
                // 主租户里被用例软删的成员（原实现用 join，H2 不支持，改为单表删除）
                st("delete from user_tenant where status = 'removed' "
                        + "and user_id in (select id from app_user where phone like '135%')"),
                st("delete from app_user where phone like '135%' "
                        + "and id not in (select user_id from user_tenant)"),
                // 最后删企业
                st("delete from org where id <> ?", demo));
    }

    /** 清理后残留断言：任何一个非 0 都说明清理有漏，必须让用例失败。 */
    private void assertNoResidue() {
        List<String> dirty = new ArrayList<>();
        for (String table : List.of("org", "tenant_role", "device", "device_reading", "product",
                "product_footprint", "carbon_asset", "accounting_period")) {
            Long left = countResidue(table);
            if (left != null && left > 0) dirty.add(table + "=" + left);
        }
        if (!dirty.isEmpty()) {
            throw new IllegalStateException("测试清理后仍有残留（org_id<>1）: " + String.join(", ", dirty));
        }
    }

    /** org 用主键判定，其余按 org_id；表不存在时记为该表失败（不静默跳过）。 */
    private Long countResidue(String table) {
        String sql = "org".equals(table)
                ? "select count(*) from org where id <> ?"
                : "select count(*) from " + table + " where org_id <> ?";
        try {
            return jdbc.queryForObject(sql, Long.class, DEMO_ORG_ID);
        } catch (DataAccessException e) {
            throw new IllegalStateException("残留检查失败: " + sql + " → " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg.replace('\n', ' ');
    }
}
