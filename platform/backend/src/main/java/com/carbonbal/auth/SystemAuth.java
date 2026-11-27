package com.carbonbal.auth;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

/** 定时任务/系统动作使用的租户上下文，避免直接依赖请求线程的 JWT。 */
public final class SystemAuth {
    private SystemAuth() {}

    public interface Action<T> {
        T run() throws Exception;
    }

    public static <T> T runAs(Long orgId, String username, Action<T> action) throws Exception {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        var auth = new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        auth.setDetails(Map.of("org", orgId));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return action.run();
        } finally {
            if (previous == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
