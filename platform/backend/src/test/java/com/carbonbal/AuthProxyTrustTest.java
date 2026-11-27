package com.carbonbal;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 默认配置下 <b>不信任</b> X-Forwarded-For（P1-3 的 fail-closed 基线）。
 * <p>
 * {@code app.auth.trusted-proxies} 默认为空 = 不信任任何代理，客户端 IP 一律取 {@code remoteAddr}。
 * 因此攻击者无论怎么伪造 XFF，锁定键始终相同，第 6 次必然 429；
 * 如果实现错误地信任了 XFF，每次请求都会落到新的 IP 桶，第 6 次会是 401 而不是 429——
 * 这正是本用例要挡住的回归。
 */
class AuthProxyTrustTest extends ApiTestSupport {

    @Test
    void untrustedForwardedForIsIgnored() throws Exception {
        String account = "nobody-spoof";
        for (int i = 1; i <= 5; i++) {
            Resp r = attempt(account, "wrong-" + i, "192.0.2." + i, "10.20.30.40");
            assertThat(r.http()).as("第 " + i + " 次应为 401").isEqualTo(401);
        }
        Resp locked = attempt(account, "wrong-6", "192.0.2.250", "10.20.30.40");
        assertThat(locked.http())
                .as("默认不信任 XFF：伪造 IP 不能让锁定计数清零")
                .isEqualTo(429);
        assertThat(locked.code()).isEqualTo("AUTH_RATE_LIMITED");
    }

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
