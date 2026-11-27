package com.carbonbal;

import com.carbonbal.auth.JwtAuthFilter;
import com.carbonbal.auth.JwtService;
import com.carbonbal.auth.AuthStateService;
import com.carbonbal.auth.RevokedTokenMapper;
import com.carbonbal.auth.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 退出登录必须真正吊销会话（BUG-001 / TC-AUTH-09）。
 * <p>
 * 历史缺陷：{@code AuthController.logout()} 只清 Cookie，令牌本身仍然有效
 * （实测 logout 后同一 Bearer 访问 {@code /api/products} 仍是 200）。
 * 本类锁定四条语义：
 * <ol>
 *   <li>登出后同一令牌立刻 401 {@code SESSION_REVOKED}；</li>
 *   <li>同一账号的<b>另一台设备</b>令牌仍然 200（按会话 jti 吊销，不是全设备下线）；</li>
 *   <li>吊销<b>持久化</b>：重建过滤器（模拟进程重启、内存缓存清空）后仍然 401；</li>
 *   <li>登出接口保持幂等：无令牌/重复登出都返回 200。</li>
 * </ol>
 */
class SessionRevocationTest extends ApiTestSupport {

    private static final String ADMIN = "13800000001";
    private static final String PWD = "Admin@2026!Pass";

    @Autowired
    private JwtService jwt;

    @Autowired
    private AuthStateService authState;

    @Autowired
    private RevokedTokenMapper revokedTokenMapper;

    @Autowired
    private TokenRevocationService revocation;

    @Test
    void logoutRevokesCurrentSession() throws Exception {
        String token = login(ADMIN, PWD);
        assertThat(call(get("/api/products"), token, null).http())
                .as("登出前应可正常访问").isEqualTo(200);

        Resp out = call(post("/api/auth/logout"), token, null);
        assertThat(out.http()).as("登出必须 200（匿名接口，幂等）").isEqualTo(200);
        assertThat(out.body().path("loggedOut").asBoolean()).isTrue();

        Resp after = call(get("/api/products"), token, null);
        assertThat(after.http()).as("登出后同一 Bearer 必须 401").isEqualTo(401);
        assertThat(after.code()).as("错误码需明确表示会话已被吊销").isEqualTo("SESSION_REVOKED");

        // /api/auth/me 同样失效（不能只拦业务接口留下"半活"会话）
        assertThat(call(get("/api/auth/me"), token, null).http()).isEqualTo(401);

        // 重新登录拿到新会话（新 jti）后恢复可用
        String fresh = login(ADMIN, PWD);
        assertThat(call(get("/api/products"), fresh, null).http())
                .as("重新登录的新会话正常").isEqualTo(200);
    }

    @Test
    void logoutOnlyRevokesCurrentDevice() throws Exception {
        // 同一账号两次登录 = 两台设备，jti 不同
        String deviceA = login(ADMIN, PWD);
        String deviceB = login(ADMIN, PWD);
        String jtiA = jwt.parse(deviceA).getId();
        String jtiB = jwt.parse(deviceB).getId();
        assertThat(jtiA).as("每次签发都应有独立 jti").isNotBlank().isNotEqualTo(jtiB);

        call(post("/api/auth/logout"), deviceA, null);

        assertThat(call(get("/api/products"), deviceA, null).http())
                .as("A 设备登出后必须 401").isEqualTo(401);
        assertThat(call(get("/api/products"), deviceB, null).http())
                .as("B 设备（另一会话）不受影响，仍应 200").isEqualTo(200);
    }

    @Test
    void revocationSurvivesRestart() throws Exception {
        String token = login(ADMIN, PWD);
        String jti = jwt.parse(token).getId();
        call(post("/api/auth/logout"), token, null);

        // 落库校验：记录存在且过期时间在未来（过期即失效，也便于定时清理）
        Long rows = jdbc.queryForObject("select count(*) from revoked_token where jti = ?", Long.class, jti);
        assertThat(rows).as("吊销记录必须落库（不是内存 Map）").isEqualTo(1L);
        LocalDateTime exp = jdbc.queryForObject(
                "select expires_at from revoked_token where jti = ?", LocalDateTime.class, jti);
        assertThat(exp).as("吊销记录的过期时间应晚于当前时间").isAfter(LocalDateTime.now());

        // 相当于进程重启：全新的服务实例（内存缓存为空）只能依赖数据库
        TokenRevocationService afterRestart = new TokenRevocationService(revokedTokenMapper);
        assertThat(afterRestart.isRevoked(jti)).as("重启后仍应判定为已吊销").isTrue();

        // 重建过滤器后走完整过滤器链路，确认仍然是 401（不是靠缓存/上下文侥幸）
        JwtAuthFilter rebuilt = new JwtAuthFilter(jwt, authState, afterRestart);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/products");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        rebuilt.doFilter(req, res, (rq, rs) -> chained.set(true));
        assertThat(res.getStatus()).as("重建过滤器后仍必须 401").isEqualTo(401);
        assertThat(chained.get()).as("不得继续放行到后续过滤器").isFalse();
    }

    @Test
    void logoutIsIdempotentWithoutToken() throws Exception {
        // 没有令牌（或浏览器已清 Cookie）时登出仍应 200，不能因为"无会话可吊销"而报错
        assertThat(call(post("/api/auth/logout"), null, null).http()).isEqualTo(200);

        String token = login(ADMIN, PWD);
        assertThat(call(post("/api/auth/logout"), token, null).http()).isEqualTo(200);
        // 重复登出同样幂等
        assertThat(call(post("/api/auth/logout"), token, null).http()).isEqualTo(200);
        assertThat(call(get("/api/products"), token, null).http()).isEqualTo(401);
    }

    @Test
    void revocationTableIsPurgeable() throws Exception {
        String token = login(ADMIN, PWD);
        String jti = jwt.parse(token).getId();
        call(post("/api/auth/logout"), token, null);
        // 把过期时间改成过去，模拟"令牌已到期"，清理任务应能删除
        jdbc.update("update revoked_token set expires_at = ? where jti = ?",
                LocalDateTime.now().minusMinutes(1), jti);
        assertThat(revocation.purgeExpired()).as("应删除过期的吊销记录").isGreaterThanOrEqualTo(1);
        Long left = jdbc.queryForObject("select count(*) from revoked_token where jti = ?", Long.class, jti);
        assertThat(left).as("过期记录必须被清理，避免表无限增长").isZero();
    }
}
