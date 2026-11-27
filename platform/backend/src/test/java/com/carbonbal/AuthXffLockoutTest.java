package com.carbonbal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 轮换 X-Forwarded-For 不能绕过登录锁定（P1-3）。
 * <p>
 * 历史实现直接取 XFF 的首段做锁定键，攻击者每次请求换一个伪造 IP 就能把「账号+IP」计数清零。
 * 这里在"可信代理=127.0.0.1"的配置下连续 6 次换 XFF、每次带正确图形码，
 * 第 6 次仍必须 429——因为还有「账号」维度的第二把锁。
 * <p>
 * 账号维度阈值在本类压到 5，确保用例能在 6 次内触达；生产默认 20（防恶意锁定他人）。
 */
@SpringBootTest(properties = {
        "app.auth.trusted-proxies=127.0.0.1",
        "app.auth.max-attempts=5",
        "app.auth.max-attempts-account=5"
})
class AuthXffLockoutTest extends ApiTestSupport {

    @Test
    void rotatingForwardedForDoesNotResetLockout() throws Exception {
        String account = "nobody-xff";
        for (int i = 1; i <= 5; i++) {
            Resp r = attempt(account, "wrong-" + i, "203.0.113." + i, "127.0.0.1");
            assertThat(r.http()).as("第 " + i + " 次（伪造 XFF 203.0.113." + i + "）应为 401").isEqualTo(401);
        }
        Resp locked = attempt(account, "wrong-6", "203.0.113.200", "127.0.0.1");
        assertThat(locked.http()).as("换掉 XFF 后第 6 次仍必须锁定").isEqualTo(429);
        assertThat(locked.code()).isEqualTo("AUTH_RATE_LIMITED");
        assertThat(locked.body().path("message").asText()).contains("登录失败次数过多");
    }

    @Test
    void otherAccountsAreNotAffectedByForeignLockout() throws Exception {
        String account = "nobody-xff2";
        for (int i = 1; i <= 5; i++) {
            attempt(account, "wrong-" + i, "198.51.100." + i, "127.0.0.1");
        }
        assertThat(attempt(account, "wrong-6", "198.51.100.9", "127.0.0.1").http()).isEqualTo(429);
        // 另一账号同来源不应被牵连（锁是账号维度的，不是全局熔断）
        Resp other = attempt("admin", "Admin@2026!Pass", "198.51.100.10", "127.0.0.1");
        assertThat(other.http()).as("其它账号不应被误锁").isEqualTo(200);
    }

    /** 指定 remoteAddr 与 XFF 的登录尝试（每次重新取图形码，确保失败原因只可能是密码）。 */
    private Resp attempt(String account, String pwd, String xff, String remoteAddr) throws Exception {
        String[] cap = captcha();
        MockHttpServletRequestBuilder req = post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                });
        if (xff != null) req.header("X-Forwarded-For", xff);
        return call(req, null, Map.of(
                "username", account, "password", pwd,
                "captchaId", cap[0], "captchaCode", cap[1]));
    }
}
