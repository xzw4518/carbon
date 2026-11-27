package com.carbonbal;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 退出登录：必须放行（不依赖鉴权）、清掉会话 Cookie，并让浏览器回到登录页。
 * 历史缺陷：JwtAuthFilter 把 /api/auth/logout 当匿名接口，但 SecurityConfig 未放行，导致 401、Cookie 未清。
 */
class LogoutTest extends ApiTestSupport {

    @Test
    void logoutIsPublicAndClearsCookies() throws Exception {
        String[] cap = captcha();
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"phone\":\"13800000001\",\"password\":\"Admin@2026!Pass\","
                                + "\"captchaId\":\"%s\",\"captchaCode\":\"%s\"}", cap[0], cap[1])))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);

        Cookie token = cookieOf(login, "cb_token");
        Cookie csrf = cookieOf(login, "cb_csrf");
        assertThat(token).as("登录应下发会话 Cookie").isNotNull();
        assertThat(csrf).as("登录应下发 CSRF Cookie").isNotNull();

        // 不带 X-CSRF-Token 也必须能退出：退出接口已加入 CSRF 豁免
        MvcResult out = mvc.perform(post("/api/auth/logout").cookie(token, csrf)).andReturn();
        assertThat(out.getResponse().getStatus()).as("退出必须 200，不能 401").isEqualTo(200);

        List<String> setCookies = out.getResponse().getHeaders("Set-Cookie");
        assertThat(String.join("\n", setCookies))
                .as("会话 Cookie 必须被清除").contains("cb_token=").contains("Max-Age=0");

        // 浏览器侧拿到的是失效 Cookie：再访问 me 应为未登录
        Cookie expired = new Cookie("cb_token", "");
        Resp me = call(get("/api/auth/me").cookie(expired), null, null);
        assertThat(me.http()).isEqualTo(401);
    }

    private static Cookie cookieOf(MvcResult res, String name) {
        Cookie[] cookies = res.getResponse().getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (name.equals(c.getName())) return c;
        return null;
    }
}
