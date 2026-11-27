package com.carbonbal.auth;

import com.carbonbal.identity.PageCatalog;
import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

/** 从令牌读取页面集合：`*` 表示全部（租户管理员 / 平台账号）。 */
public final class PageAccess {

    private PageAccess() {}

    public static String raw() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return "*";
        Object d = a.getDetails();
        Object v = null;
        if (d instanceof Claims c) v = c.get("pages");
        else if (d instanceof java.util.Map<?, ?> m) v = m.get("pages");
        return v == null ? "*" : String.valueOf(v);
    }

    public static boolean all() {
        return "*".equals(raw());
    }

    public static List<String> current() {
        String raw = raw();
        if ("*".equals(raw)) return PageCatalog.CODES;
        if ("-".equals(raw)) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** null 视为“不受页面限制”（系统接口由 @PreAuthorize 管）。 */
    public static boolean allows(String code) {
        if (code == null) return true;
        if (all()) return true;
        return ("," + raw() + ",").contains("," + code + ",");
    }

    /** 命中任一页面即可：附件同时属于原始凭证与第三方报告两个页面。 */
    public static boolean allowsAny(String... codes) {
        if (codes == null || codes.length == 0) return true;
        if (all()) return true;
        for (String code : codes) {
            if (allows(code)) return true;
        }
        return false;
    }
}
