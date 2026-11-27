package com.carbonbal.auth;

import com.carbonbal.identity.LoginLog;
import com.carbonbal.identity.LoginLogMapper;
import com.carbonbal.identity.UserTenant;
import com.carbonbal.identity.UserTenantMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌有效性校验：账号状态、令牌版本、成员关系、租户状态。
 * <p>
 * 每个请求都查库代价太高，这里按账号缓存 30 秒；改密、停用、改角色等写操作会显式失效。
 * 多实例部署时替换为 Redis（接口不变）。
 */
@Service
public class AuthStateService {

    public record AccountState(Long userId, String phone, String displayName, String status,
                               int tokenVersion, boolean mustChangePassword,
                               boolean platformAdmin,
                               LocalDateTime passwordUpdatedAt, LocalDateTime tempPasswordExpiresAt) {}

    public record MemberState(Long orgId, String orgName, String role, String status,
                              String orgStatus, String userType, Long roleId) {}

    private static final long TTL_MILLIS = 30_000L;

    private final AppUserMapper users;
    private final UserTenantMapper members;
    private final com.carbonbal.org.OrgMapper orgs;
    private final LoginLogMapper loginLogs;
    private final Map<Long, CacheEntry<AccountState>> accountCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<MemberState>> memberCache = new ConcurrentHashMap<>();

    public AuthStateService(AppUserMapper users, UserTenantMapper members,
                            com.carbonbal.org.OrgMapper orgs, LoginLogMapper loginLogs) {
        this.users = users;
        this.members = members;
        this.orgs = orgs;
        this.loginLogs = loginLogs;
    }

    public AccountState account(Long userId) {
        CacheEntry<AccountState> c = accountCache.get(userId);
        if (c != null && c.alive()) return c.value;
        AppUser u = users.selectById(userId);
        if (u == null) return null;
        AccountState st = new AccountState(u.getId(), u.getPhone(), u.getDisplayName(),
                u.getStatus() == null ? "active" : u.getStatus(),
                u.getTokenVersion() == null ? 0 : u.getTokenVersion(),
                Boolean.TRUE.equals(u.getMustChangePassword()),
                Boolean.TRUE.equals(u.getPlatformAdmin()),
                u.getPasswordUpdatedAt(), u.getTempPasswordExpiresAt());
        accountCache.put(userId, new CacheEntry<>(st));
        return st;
    }

    public MemberState member(Long orgId, Long userId) {
        String key = orgId + ":" + userId;
        CacheEntry<MemberState> c = memberCache.get(key);
        if (c != null && c.alive()) return c.value;
        UserTenant t = members.selectOne(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, orgId).eq(UserTenant::getUserId, userId).last("LIMIT 1"));
        if (t == null) return null;
        com.carbonbal.org.Org o = orgs.selectById(orgId);
        // 旧数据可能还是 filler/reviewer 等岗位名，统一归一为 admin / user
        String tier = "admin".equals(t.getRole()) ? "admin" : "user";
        MemberState st = new MemberState(orgId, o == null ? "" : o.getName(), tier,
                t.getStatus(), o == null ? "active" : (o.getStatus() == null ? "active" : o.getStatus()),
                t.getUserType() == null ? ("admin".equals(t.getRole()) ? "tenant_admin" : "user") : t.getUserType(),
                t.getRoleId());
        memberCache.put(key, new CacheEntry<>(st));
        return st;
    }

    public void invalidate(Long userId) {
        if (userId == null) return;
        accountCache.remove(userId);
        memberCache.keySet().removeIf(k -> k.endsWith(":" + userId));
    }

    public void invalidateMember(Long orgId, Long userId) {
        memberCache.remove(orgId + ":" + userId);
        accountCache.remove(userId);
    }

    /** 登录流水：成功与失败都记，用于风控与"最近登录"展示。 */
    public void logLogin(Long orgId, Long userId, String phone, String result, String channel,
                         String ip, String userAgent) {
        LoginLog l = new LoginLog();
        l.setOrgId(orgId);
        l.setUserId(userId);
        l.setPhone(phone == null ? "" : phone);
        l.setResult(result);
        l.setChannel(channel);
        l.setIp(ip);
        l.setUserAgent(userAgent != null && userAgent.length() > 250 ? userAgent.substring(0, 250) : userAgent);
        l.setAt(LocalDateTime.now());
        loginLogs.insert(l);
    }

    /** 每天清理 90 天前的登录流水，避免无限增长。 */
    @Scheduled(cron = "0 20 3 * * *")
    public void purgeLoginLogs() {
        loginLogs.delete(Wrappers.<LoginLog>lambdaQuery()
                .lt(LoginLog::getAt, LocalDateTime.now().minusDays(90)));
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final long at = System.currentTimeMillis();

        private CacheEntry(T value) {
            this.value = value;
        }

        private boolean alive() {
            return System.currentTimeMillis() - at < TTL_MILLIS;
        }
    }
}
