package com.carbonbal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.common.Hashes;
import com.carbonbal.identity.UserTenant;
import com.carbonbal.identity.UserTenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 演示账号种子：admin / filler / viewer，手机号 13800000001~3。
 * 生产环境通过 app.seed.enabled=false 关闭。
 */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppUserMapper users;
    private final UserTenantMapper members;
    private final PasswordEncoder encoder;
    private final com.carbonbal.identity.RoleService roles;
    private final boolean enabled;

    public DataSeeder(AppUserMapper users, UserTenantMapper members, PasswordEncoder encoder,
                      com.carbonbal.identity.RoleService roles,
                      @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users;
        this.members = members;
        this.encoder = encoder;
        this.roles = roles;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        seed("admin", "13800000001", "Admin@2026!Pass", "管理员", "admin");
        seed("filler", "13800000002", "Test@2026!Pass", "填报员", "filler");
        seed("reviewer", "13800000004", "Test@2026!Pass", "复核人", "reviewer");
        seed("approver", "13800000005", "Test@2026!Pass", "批准人", "approver");
        seed("viewer", "13800000003", "Test@2026!Pass", "查看者", "viewer");
        seedPlatform("13900000000", "Superadmin@2026!Pass", "平台运营");
    }

    /** 平台运营账号：不属于任何企业，只能进平台端。 */
    private void seedPlatform(String phone, String raw, String display) {
        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone).last("LIMIT 1"));
        if (u != null) {
            if (!Boolean.TRUE.equals(u.getPlatformAdmin())) {
                users.update(null, Wrappers.<AppUser>lambdaUpdate().eq(AppUser::getId, u.getId())
                        .set(AppUser::getPlatformAdmin, true));
            }
            return;
        }
        u = new AppUser();
        u.setPhone(phone);
        u.setDisplayName(display);
        u.setPasswordHash(encoder.encode(raw));
        u.setPasswordAlgo("bcrypt");
        u.setStatus("active");
        u.setEnabled(true);
        u.setTokenVersion(0);
        u.setMustChangePassword(false);
        u.setPlatformAdmin(true);
        u.setFailedCount(0);
        u.setPasswordUpdatedAt(LocalDateTime.now());
        u.setCreatedAt(LocalDateTime.now());
        users.insert(u);
        log.info("seeded platform admin phone={}", Hashes.maskPhone(phone));
    }

    private void seed(String username, String phone, String raw, String display, String role) {
        boolean tenantAdmin = "admin".equals(role);
        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, username).last("LIMIT 1"));
        if (u == null) {
            u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                    .eq(AppUser::getPhone, phone).last("LIMIT 1"));
        }
        if (u == null) {
            u = new AppUser();
            u.setUsername(username);
            u.setPhone(phone);
            u.setPasswordHash(encoder.encode(raw));
            u.setDisplayName(display);
            u.setRole(role);
            u.setOrgId(1L);
            u.setEnabled(true);
            u.setStatus("active");
            u.setTokenVersion(0);
            u.setMustChangePassword(false);
            u.setFailedCount(0);
            u.setPasswordUpdatedAt(LocalDateTime.now());
            u.setLastOrgId(1L);
            u.setCreatedAt(LocalDateTime.now());
            users.insert(u);
            log.info("seeded user {} phone={} role={}", username, Hashes.maskPhone(phone), role);
        } else {
            // 老数据补手机号与状态，保持可登录
            var upd = Wrappers.<AppUser>lambdaUpdate().eq(AppUser::getId, u.getId());
            boolean dirty = false;
            if (u.getPhone() == null || u.getPhone().isBlank()) { upd.set(AppUser::getPhone, phone); dirty = true; }
            if (u.getStatus() == null) { upd.set(AppUser::getStatus, "active"); dirty = true; }
            if (u.getPasswordUpdatedAt() == null) { upd.set(AppUser::getPasswordUpdatedAt, LocalDateTime.now()); dirty = true; }
            if (dirty) users.update(null, upd);
        }
        Long n = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getUserId, u.getId()).eq(UserTenant::getOrgId, 1L));
        if (n == null || n == 0) {
            UserTenant t = new UserTenant();
            t.setOrgId(1L);
            t.setUserId(u.getId());
            t.setRole(tenantAdmin ? "admin" : "user");
            t.setUserType(tenantAdmin ? "tenant_admin" : "user");
            t.setRoleId(tenantAdmin ? null : roles.defaultRoleId(1L));
            t.setStatus("active");
            t.setJoinedAt(LocalDateTime.now());
            t.setVersion(0);
            t.setCreatedBy(username);
            t.setUpdatedBy(username);
            t.setCreatedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            members.insert(t);
            log.info("seeded membership user={} org=1 role={}", username, role);
        }
    }
}
