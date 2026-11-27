package com.carbonbal;

import com.carbonbal.auth.JwtSecretPolicy;
import com.carbonbal.auth.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 令牌声明与密钥策略（P0-2 / P1-2）。
 * <p>
 * 覆盖三件事：
 * <ol>
 *   <li>弱密钥 / 已知默认密钥必须让应用启动失败（不是警告）；</li>
 *   <li>令牌缺少 {@code pages} 声明 → 访问业务接口 403（默认拒绝，不再当 `*` 放行）；</li>
 *   <li>令牌缺少 {@code uid/org} 声明 → 401 重新登录；{@code imp} 代管只允许平台账号。</li>
 * </ol>
 * 为了让"缺声明"的令牌仍能通过签名校验，本类把密钥固定为测试专用强密钥，
 * 再用同一密钥自签令牌——这正是攻击者拿到泄露密钥后能做的事，所以必须验到位。
 */
@SpringBootTest(properties = {
        "app.jwt.secret=" + TokenClaimTest.FIXED_SECRET,
        "app.jwt.dev-auto-secret=false"
})
class TokenClaimTest extends ApiTestSupport {

    /** 测试专用强密钥（≥32 位、去重字符 ≥12、不在黑名单前缀内）。 */
    static final String FIXED_SECRET = "unit-test-only-9f3a7c1e5b8d2a4f6c0e3b7d9a1f5c8e2b6d4a70";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(FIXED_SECRET.getBytes(StandardCharsets.UTF_8));

    /* ==================== 1. 密钥策略 ==================== */

    @Test
    void knownDefaultSecretIsRejected() {
        // 历史 dev 默认值、env.sh 历史默认值都必须被拦
        for (String bad : new String[]{
                "dev-only-key-please-replace-in-production-0123456789",
                "carbonbal-dev-secret-0123456789abcdef",
                "local-dev-2f8a1c9e4b7d6a3f5e8c1b4d7a2f9e6c-local",
                "please-replace-with-a-random-secret-0123456789",
                "changeme-changeme-changeme-changeme-1234"}) {
            assertThat(JwtSecretPolicy.isKnownBad(bad)).as("应识别为已知默认密钥: " + bad).isTrue();
            assertThatThrownBy(() -> new JwtService(bad, 8, false))
                    .as("已知默认密钥必须让启动失败")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void weakSecretIsRejected() {
        // 空、过短、低熵三种弱密钥
        assertThatThrownBy(() -> new JwtService("", 8, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("short-secret", 8, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 8, false))
                .as("低熵（去重字符不足）必须拒绝")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void weakSecretFailsContextStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(JwtOnlyConfig.class);
        runner.withPropertyValues("app.jwt.secret=dev-only-key-please-replace-in-production-0123456789",
                        "app.jwt.dev-auto-secret=false")
                .run(ctx -> assertThat(ctx).as("弱密钥下 Spring 上下文必须起不来").hasFailed());
        runner.withPropertyValues("app.jwt.secret=", "app.jwt.dev-auto-secret=false")
                .run(ctx -> assertThat(ctx).as("无密钥且未开 dev 兜底时必须起不来").hasFailed());
        runner.withPropertyValues("app.jwt.secret=", "app.jwt.dev-auto-secret=true")
                .run(ctx -> assertThat(ctx).as("dev 兜底可启动").hasNotFailed());
    }

    @Test
    void devAutoSecretGeneratesUsableKey() {
        JwtService service = new JwtService("", 8, true);
        assertThat(service).as("dev 兜底生成的临时密钥应可用").isNotNull();
    }

    /* ==================== 2. pages 缺失 → 403 ==================== */

    @Test
    void tokenWithoutPagesClaimIsForbidden() throws Exception {
        SeedUser admin = seedUser("13800000001");
        String token = sign(admin, /* pages */ null, /* impersonation */ false);

        Resp r = call(get("/api/products"), token, null);
        assertThat(r.http()).as("缺 pages 声明的令牌访问业务接口应 403").isEqualTo(403);
        assertThat(r.code()).isEqualTo("PAGE_FORBIDDEN");

        // 空白 pages 声明同样按空集处理（不再是 fail-open）
        String blank = sign(admin, "  ", false);
        assertThat(call(get("/api/products"), blank, null).http()).isEqualTo(403);

        // 无页面集合的平台账号声明（"-"）也进不了业务接口
        String none = sign(admin, "-", false);
        assertThat(call(get("/api/products"), none, null).http()).isEqualTo(403);
    }

    /* ==================== 3. uid/org 缺失 → 401，imp 只允许平台账号 ==================== */

    @Test
    void tokenWithoutIdentityClaimsIsRejected() throws Exception {
        SeedUser admin = seedUser("13800000001");
        String noUid = Jwts.builder()
                .subject("13800000001")
                .claim("role", "admin")
                .claim("org", admin.orgId())
                .claim("tv", admin.tokenVersion())
                .claim("pages", "*")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(KEY).compact();
        Resp r = call(get("/api/products"), noUid, null);
        assertThat(r.http()).as("缺 uid 声明的老令牌必须重新登录").isEqualTo(401);
        assertThat(r.code()).isEqualTo("SESSION_REVOKED");

        String noOrg = Jwts.builder()
                .subject("13800000001")
                .claim("uid", admin.userId())
                .claim("role", "admin")
                .claim("tv", admin.tokenVersion())
                .claim("pages", "*")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(KEY).compact();
        assertThat(call(get("/api/products"), noOrg, null).http()).isEqualTo(401);
    }

    @Test
    void impersonationTokenIsOnlyForPlatformAdmin() throws Exception {
        SeedUser filler = seedUser("13800000002");
        String fakeImp = sign(filler, "*", true);
        Resp r = call(get("/api/products"), fakeImp, null);
        assertThat(r.http()).as("非平台账号的 imp 令牌必须 403").isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN");

        // 反向：平台账号代管可以进入租户业务接口
        SeedUser platform = seedUser("13900000000");
        String imp = Jwts.builder()
                .subject("13900000000")
                .claim("uid", platform.userId())
                .claim("phone", "13900000000")
                .claim("role", "admin")
                .claim("org", DEMO_ORG_ID)
                .claim("tv", platform.tokenVersion())
                .claim("pages", "*")
                .claim("imp", true)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(KEY).compact();
        assertThat(call(get("/api/products"), imp, null).http())
                .as("平台账号代管令牌应可用").isEqualTo(200);
    }

    /** 未改初始密码前会被 403 拦掉，这里直接用一条已改密的账号做声明断言。 */
    @Test
    void validTokenStillWorks() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        assertThat(call(get("/api/products"), admin, null).http())
                .as("正常登录令牌不受收紧策略影响").isEqualTo(200);
        assertThat(status(MockMvcRequestBuilders.get("/api/export?resource=products"), admin)).isEqualTo(200);
    }

    /* ==================== 工具 ==================== */

    private record SeedUser(long userId, long orgId, int tokenVersion) {}

    private SeedUser seedUser(String phone) {
        Long id = jdbc.queryForObject("select id from app_user where phone = ?", Long.class, phone);
        Integer tv = jdbc.queryForObject("select token_version from app_user where id = ?", Integer.class, id);
        // 平台账号没有任何成员关系，此时回落演示租户（仅用于构造 org 声明）
        java.util.List<Long> orgs = jdbc.queryForList(
                "select org_id from user_tenant where user_id = ? and status = 'active'", Long.class, id);
        Long orgId = orgs.isEmpty() ? DEMO_ORG_ID : orgs.get(0);
        return new SeedUser(id, orgId, tv == null ? 0 : tv);
    }

    /**
     * 用固定密钥自签令牌：模拟"拿到密钥的攻击者"或"升级前签发的老令牌"。
     *
     * @param user         账号
     * @param pages        页面声明；null 表示不写该声明
     * @param impersonation 是否写 imp 声明
     */
    private String sign(SeedUser user, String pages, boolean impersonation) {
        var b = Jwts.builder()
                .subject("user-" + user.userId())
                .claim("uid", user.userId())
                .claim("role", "admin")
                .claim("org", user.orgId())
                .claim("tv", user.tokenVersion())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
        if (pages != null) b.claim("pages", pages);
        if (impersonation) b.claim("imp", true);
        return b.signWith(KEY).compact();
    }

}

/**
 * 只为验证"弱密钥 → 构造器抛异常 → 上下文起不来"的最小配置。
 * <p>
 * 刻意声明为<b>顶级</b>类：若放在测试类内部并标注 {@code @Configuration}，
 * {@code @SpringBootTest} 会把它当成该测试的上下文配置，导致整个 Spring 应用配置被替换。
 */
@Configuration
class JwtOnlyConfig {
    @Bean
    JwtService jwtService(@Value("${app.jwt.secret:}") String secret,
                          @Value("${app.jwt.ttl-hours:8}") long ttlHours,
                          @Value("${app.jwt.dev-auto-secret:false}") boolean devAutoSecret) {
        return new JwtService(secret, ttlHours, devAutoSecret);
    }
}
