package com.carbonbal.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与验签。
 * <p>
 * 密钥来自环境变量 {@code JWT_SECRET}，由 {@link JwtSecretPolicy} 做强校验：
 * 空、过短、低熵、命中已知默认值前缀一律启动失败（P0-2）。
 * 仅 dev profile 可通过 {@code app.jwt.dev-auto-secret=true} 生成一次性随机密钥，
 * 并打印 WARN 提示（不打印密钥值），避免"默认密钥写死在仓库里"。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${app.jwt.secret:}") String secret,
                      @Value("${app.jwt.ttl-hours:8}") long ttlHours,
                      @Value("${app.jwt.dev-auto-secret:false}") boolean devAutoSecret) {
        String effective = secret == null ? "" : secret.trim();
        if (effective.isEmpty()) {
            if (!devAutoSecret) {
                // 无密钥 + 非 dev：直接失败，绝不放行
                JwtSecretPolicy.validate(effective);
            }
            effective = JwtSecretPolicy.generate();
            log.warn("JWT_SECRET 未配置：本次启动已生成临时密钥（仅限 dev/演示）。"
                    + "重启后所有已签发令牌失效；生产请用 bash deploy/gen-secret.sh 注入固定密钥。");
        } else {
            JwtSecretPolicy.validate(effective);
        }
        this.key = Keys.hmacShaKeyFor(effective.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    /** 兼容旧调用：按账号自身的 org/role 签发。 */
    public String issue(AppUser u) {
        return issue(u, u.getOrgId(), u.getRole());
    }

    /** 正式签发：租户与角色来自成员关系，token_version 用于改密/停用后即时失效。 */
    public String issue(AppUser u, Long orgId, String role) {
        return issue(u, orgId, role, "*", false);
    }

    /**
     * 超级管理员进入租户：role 固定为 admin（在该租户内拥有全部权限），
     * 额外带 imp=true 供过滤器识别，跳过成员与租户状态校验。
     */
    public String issue(AppUser u, Long orgId, String role, boolean impersonation) {
        return issue(u, orgId, role, "*", impersonation);
    }

    /**
     * @param pages 页面集合，逗号分隔；`*` 表示全部（租户管理员与平台账号）
     */
    public String issue(AppUser u, Long orgId, String role, String pages, boolean impersonation) {
        Instant now = Instant.now();
        String subject = u.getPhone() != null && !u.getPhone().isBlank() ? u.getPhone() : u.getUsername();
        var b = Jwts.builder()
                .subject(subject)
                .claim("uid", u.getId())
                .claim("phone", subject)
                .claim("role", role)
                .claim("name", u.getDisplayName())
                .claim("org", orgId)
                .claim("tv", u.getTokenVersion() == null ? 0 : u.getTokenVersion())
                .claim("pages", pages == null ? "*" : (pages.isBlank() ? "-" : pages))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)));
        if (impersonation) b.claim("imp", true);
        return b.signWith(key).compact();
    }

    /** 多租户时的临时令牌：只能用于选择企业，5 分钟有效。 */
    public String issueTemp(AppUser u) {
        Instant now = Instant.now();
        String subject = u.getPhone() != null && !u.getPhone().isBlank() ? u.getPhone() : u.getUsername();
        return Jwts.builder()
                .subject(subject)
                .claim("uid", u.getId())
                .claim("phone", subject)
                .claim("scope", "tenant-select")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
