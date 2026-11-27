package com.carbonbal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF 防护（double-submit）：令牌放在 HttpOnly Cookie 里，浏览器会自动携带；
 * 写请求必须把非 HttpOnly 的 cb_csrf 值放到 X-CSRF-Token 头里，跨站请求伪造不了。
 * 用 Bearer 头的客户端（脚本、e2e）不依赖 Cookie，直接放行。
 */
@Component
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT = Set.of(
            "/api/auth/login", "/api/auth/sms/send", "/api/auth/sms/login",
            "/api/auth/tenant/select", "/api/auth/captcha",
            // 退出必须永远可用：CSRF 令牌缺失时也要能清掉会话 Cookie
            "/api/auth/logout"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String method = req.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }
        if (EXEMPT.contains(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }
        String csrfCookie = cookie(req, "cb_csrf");
        if (csrfCookie == null || csrfCookie.isBlank()) {
            // 未使用 Cookie 会话（如未登录的匿名写请求），交给鉴权层返回 401
            chain.doFilter(req, res);
            return;
        }
        String token = req.getHeader("X-CSRF-Token");
        if (!csrfCookie.equals(token)) {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"status\":\"CSRF_INVALID\",\"body\":{\"message\":\"请求校验失败，请刷新页面后重试\"}}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
