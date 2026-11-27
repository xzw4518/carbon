package com.carbonbal.auth;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    /** 当前租户：来自 JWT 的 org 声明；无认证时回落 1（单测与种子数据）。 */
    public static Long orgId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return 1L;
        Object d = a.getDetails();
        Object v = null;
        if (d instanceof Claims c) v = c.get("org");
        else if (d instanceof java.util.Map<?, ?> m) v = m.get("org");
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) { }
        }
        return 1L;
    }

    /** 当前账号 id：来自 JWT 的 uid 声明；旧令牌无此声明时返回 null。 */
    public static Long userIdOrNull() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return null;
        Object v = claim(a, "uid");
        if (v instanceof Number n) return n.longValue();
        if (v != null && !v.toString().isBlank()) {
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** 当前账号手机号：来自 JWT 的 phone 声明；旧令牌回落为 subject。 */
    public static String phone() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return "";
        Object v = claim(a, "phone");
        if (v != null && !v.toString().isBlank()) return v.toString();
        return a.getName();
    }

    /** 操作人标识：写入 created_by / updated_by / 审计 actor（新数据为手机号）。 */
    public static String actor() {
        return username();
    }

    private static Object claim(Authentication a, String key) {
        Object d = a.getDetails();
        if (d instanceof Claims c) return c.get(key);
        if (d instanceof java.util.Map<?, ?> m) return m.get(key);
        return null;
    }

    public static String role() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getAuthorities().isEmpty()) return "";
        return a.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "").toLowerCase();
    }

    /** 当前令牌的页面集合；`*` 表示全部。 */
    public static java.util.List<String> pages() {
        return PageAccess.current();
    }

    /** 是否处于“超级管理员进入租户”的代管上下文。 */
    public static boolean impersonating() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return false;
        Object v = claim(a, "imp");
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }
}
