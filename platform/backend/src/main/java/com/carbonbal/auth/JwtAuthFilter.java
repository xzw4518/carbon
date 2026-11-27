package com.carbonbal.auth;

import com.carbonbal.identity.PageCatalog;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证过滤器：优先读 HttpOnly Cookie，回退 Authorization: Bearer（脚本与 e2e 使用）。
 * <p>
 * 除解析签名外，还校验账号状态、令牌版本（改密/停用/改角色后失效）、页面集合、成员关系与租户状态。
 * <p>
 * 安全基线是 <b>默认拒绝</b>：令牌里缺少 {@code pages} 声明视为"没有任何页面"（业务接口 403），
 * 缺少 {@code uid/org} 声明视为会话已失效（401 重新登录），不存在任何"缺失即放行"的分支（P1-2）。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 通配页面声明：租户管理员与平台账号进入业务接口时使用。 */
    private static final String PAGE_WILDCARD = "*";

    /** 空页面声明：平台账号（没有任何业务页面）。 */
    private static final String PAGE_NONE = "-";

    private final JwtService jwt;
    private final AuthStateService state;

    public JwtAuthFilter(JwtService jwt, AuthStateService state) {
        this.jwt = jwt;
        this.state = state;
    }

    /**
     * 只处理需要身份的请求：
     * 静态资源（页面/脚本）不需要登录；登录、发码、图形码、选租户、退出、健康检查属于匿名接口，
     * 必须跳过，否则携带过期令牌的用户会被过滤器拦住，连重新登录都做不到。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) return true;
        return ANONYMOUS.contains(uri);
    }

    private static final Set<String> ANONYMOUS = Set.of(
            "/api/auth/login", "/api/auth/sms/send", "/api/auth/sms/login",
            "/api/auth/captcha", "/api/auth/tenant/select", "/api/auth/logout",
            "/api/auth/password-policy", "/api/health"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(req, res);
            return;
        }
        String token = resolveToken(req);
        if (token == null) {
            chain.doFilter(req, res);
            return;
        }
        Claims c;
        try {
            c = jwt.parse(token);
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            chain.doFilter(req, res);
            return;
        }
        if ("tenant-select".equals(String.valueOf(c.get("scope")))) {
            chain.doFilter(req, res);
            return;
        }
        Long uid = asLong(c.get("uid"));
        Long orgId = asLong(c.get("org"));
        if (uid == null || orgId == null) {
            // 老令牌（无 uid/org 声明）无法校验账号状态与成员关系，直接要求重新登录
            deny(res, 401, "SESSION_REVOKED", "会话已失效，请重新登录");
            return;
        }

        AuthStateService.AccountState acc = state.account(uid);
        if (acc == null || !"active".equals(acc.status())) {
            deny(res, 423, "ACCOUNT_DISABLED", "账号已停用，请联系管理员");
            return;
        }
        int tv = c.get("tv") == null ? 0 : ((Number) c.get("tv")).intValue();
        if (tv != acc.tokenVersion()) {
            deny(res, 401, "SESSION_REVOKED", "会话已失效，请重新登录");
            return;
        }

        // 超级管理员进入租户：先做身份判定，再放行；冒充分支不再绕开页面门禁
        if (Boolean.TRUE.equals(c.get("imp"))) {
            if (!acc.platformAdmin()) {
                deny(res, 403, "FORBIDDEN", "无权限执行该操作");
                return;
            }
            if (!pagesOf(c).contains(PAGE_WILDCARD)) {
                deny(res, 403, "PAGE_FORBIDDEN", "没有该功能的访问权限");
                return;
            }
            authenticate(c.getSubject(), "admin", c);
            chain.doFilter(req, res);
            return;
        }

        // 页面级权限：普通用户只能访问角色勾选的页面；平台账号没有任何业务页面
        String needPage = PageCatalog.pageForUri(req.getRequestURI());
        Set<String> pages = pagesOf(c);
        if (needPage != null && !pages.contains(PAGE_WILDCARD) && !pages.contains(needPage)) {
            deny(res, 403, "PAGE_FORBIDDEN", "没有该功能的访问权限");
            return;
        }

        // 平台账号不属于任何企业，只校验账号本身
        if ("platform_admin".equalsIgnoreCase(String.valueOf(c.get("role"))) && orgId == 0L) {
            authenticate(c.getSubject(), "platform_admin", c);
            chain.doFilter(req, res);
            return;
        }

        AuthStateService.MemberState member = state.member(orgId, uid);
        if (member == null || !"active".equals(member.status())) {
            deny(res, 403, "MEMBER_DISABLED", "你已不在该企业中，请重新登录");
            return;
        }
        String orgStatus = member.orgStatus() == null ? "active" : member.orgStatus();
        if ("disabled".equals(orgStatus) || "suspended".equals(orgStatus)) {
            deny(res, 423, "TENANT_SUSPENDED", "企业已停用，请联系平台管理员");
            return;
        }
        if ("pending".equals(orgStatus)) {
            deny(res, 403, "TENANT_PENDING", "企业授权尚未生效");
            return;
        }
        // 到期严格阻断：读接口同样拒绝（D5 无宽限期）
        if ("expired".equals(orgStatus)) {
            deny(res, 403, "TENANT_EXPIRED", "企业授权已到期，请联系平台管理员续期");
            return;
        }
        if (acc.mustChangePassword() && !allowedBeforePasswordChange(req.getRequestURI())) {
            deny(res, 403, "MUST_CHANGE_PASSWORD", "请先修改初始密码");
            return;
        }

        authenticate(c.getSubject(), member.role(), c);
        chain.doFilter(req, res);
    }

    /**
     * 解析令牌页面集合：缺失或空白一律视为空集（默认拒绝）。
     *
     * @param c 已验签的令牌声明
     * @return 页面编码集合；`*` 表示全部
     */
    private static Set<String> pagesOf(Claims c) {
        Object raw = c.get("pages");
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty() || PAGE_NONE.equals(value)) return out;
        if (PAGE_WILDCARD.equals(value)) {
            out.add(PAGE_WILDCARD);
            return out;
        }
        for (String part : value.split(",")) {
            String code = part.trim();
            if (!code.isEmpty()) out.add(code);
        }
        return out;
    }

    private void authenticate(String subject, String role, Claims c) {
        var auth = new UsernamePasswordAuthenticationToken(subject, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + String.valueOf(role).toUpperCase())));
        auth.setDetails(c);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 未改初始密码前只允许这些接口。 */
    private static boolean allowedBeforePasswordChange(String uri) {
        return uri.startsWith("/api/auth/password/change")
                || uri.startsWith("/api/auth/password-policy")
                || uri.startsWith("/api/auth/me")
                || uri.startsWith("/api/auth/logout");
    }

    private static String resolveToken(HttpServletRequest req) {
        if (req.getCookies() != null) {
            for (Cookie ck : req.getCookies()) {
                if ("cb_token".equals(ck.getName()) && ck.getValue() != null && !ck.getValue().isBlank()) {
                    return ck.getValue();
                }
            }
        }
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        return null;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v != null && !v.toString().isBlank()) {
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static void deny(HttpServletResponse res, int status, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"" + code + "\",\"body\":{\"message\":\"" + message + "\"}}");
    }
}
