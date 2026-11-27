package com.carbonbal.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.AppUser;
import com.carbonbal.auth.AppUserMapper;
import com.carbonbal.auth.AuthStateService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.auth.PasswordPolicy;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Csv;
import com.carbonbal.common.Hashes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成员与账号管理：一人一账号，可加入多个企业；角色挂在成员关系上。
 * <p>
 * 安全约束：不能停用/降级/移出自己；不能移除或降级企业最后一名管理员；
 * 跨企业访问统一返回 404，不暴露存在性。
 */
@Service
public class UserService {

    /** 离职交接涉及的业务表（created_by 存手机号）。 */
    private static final List<String> HANDOVER_TABLES = List.of(
            "product", "supplier", "device", "device_reading", "facility", "carbon_asset", "carbon_trade",
            "product_footprint", "accounting_period", "production_value", "total_value",
            "evidence", "third_party_report", "retrofit", "energy_mgmt", "quota_year");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppUserMapper users;
    private final UserTenantMapper members;
    private final LoginLogMapper loginLogs;
    private final PasswordHistoryMapper passwordHistory;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuthStateService state;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final RoleService roles;

    public UserService(AppUserMapper users, UserTenantMapper members, LoginLogMapper loginLogs,
                       PasswordHistoryMapper passwordHistory,
                       PasswordEncoder encoder, PasswordPolicy policy, AuthStateService state,
                       AuditService audit, JdbcTemplate jdbc, RoleService roles) {
        this.users = users;
        this.members = members;
        this.loginLogs = loginLogs;
        this.passwordHistory = passwordHistory;
        this.encoder = encoder;
        this.policy = policy;
        this.state = state;
        this.audit = audit;
        this.jdbc = jdbc;
        this.roles = roles;
    }

    /* ==================== 查询 ==================== */

    public List<UserDtos.MemberView> list(String keyword, String role, Long roleId, String status) {
        boolean byType = "tenant_admin".equals(role) || "user".equals(role);
        List<UserTenant> list = members.selectList(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, CurrentUser.orgId())
                .ne(UserTenant::getStatus, "removed")
                .eq(byType, UserTenant::getUserType, role)
                .eq(!byType && role != null && !role.isBlank(), UserTenant::getRole, role)
                .eq(roleId != null, UserTenant::getRoleId, roleId)
                .eq(status != null && !status.isBlank(), UserTenant::getStatus, status)
                .orderByAsc(UserTenant::getId));
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(UserTenant::getUserId).toList();
        Map<Long, AppUser> byId = new HashMap<>();
        users.selectBatchIds(ids).forEach(u -> byId.put(u.getId(), u));
        Map<Long, String> lastLogin = lastLoginMap();
        Map<Long, String> roleNames = new HashMap<>();
        for (Map<String, Object> r : roles.list()) roleNames.put((Long) r.get("roleId"), String.valueOf(r.get("name")));
        List<UserDtos.MemberView> out = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        for (UserTenant t : list) {
            AppUser u = byId.get(t.getUserId());
            if (u == null) continue;
            String phone = u.getPhone() == null ? "" : u.getPhone();
            String name = u.getDisplayName() == null ? "" : u.getDisplayName();
            if (!kw.isEmpty() && !phone.contains(kw) && !name.contains(kw)) continue;
            String userType = userTypeOf(t);
            String roleName = "tenant_admin".equals(userType) ? "租户管理员"
                    : roleNames.getOrDefault(t.getRoleId(), "—");
            out.add(new UserDtos.MemberView(t.getId(), u.getId(), Hashes.maskPhone(phone), name, u.getEmail(),
                    t.getRole(), "tenant_admin".equals(userType) ? "租户管理员" : "普通用户", t.getStatus(),
                    t.getEmployeeNo(), t.getTitle(), t.getVersion(),
                    Boolean.TRUE.equals(u.getMustChangePassword()),
                    lastLogin.get(u.getId()), t.getJoinedAt() == null ? null : t.getJoinedAt().format(TS),
                    userType, t.getRoleId(), roleName));
        }
        return out;
    }

    public List<Map<String, Object>> loginLogsOf(Long membershipId, int limit) {
        UserTenant t = require(membershipId);
        return jdbc.query("select phone, result, channel, ip, at from login_log where user_id = ? "
                        + "order by id desc limit " + Math.min(Math.max(limit, 1), 100),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("phone", Hashes.maskPhone(rs.getString("phone")));
                    m.put("result", rs.getString("result"));
                    m.put("channel", rs.getString("channel"));
                    m.put("ip", rs.getString("ip"));
                    m.put("at", rs.getTimestamp("at") == null ? null : rs.getTimestamp("at").toLocalDateTime().format(TS));
                    return m;
                }, t.getUserId());
    }

    /* ==================== 新增 ==================== */

    @Transactional
    public UserDtos.CreateResp create(UserDtos.CreateReq req) {
        String userType = resolveUserType(req.userType(), req.role());
        Long roleId = "tenant_admin".equals(userType) ? null : resolveRoleId(req.roleId(), null);
        String phone = req.phone().trim();
        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone).last("LIMIT 1"));
        if (u != null) {
            Long existingMember = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                    .eq(UserTenant::getOrgId, CurrentUser.orgId())
                    .eq(UserTenant::getUserId, u.getId())
                    .eq(UserTenant::getStatus, "active"));
            if (existingMember != null && existingMember > 0) {
                throw BizException.duplicate("该手机号已是本企业成员");
            }
        }
        assertSeatAvailable();
        String tempPassword = null;
        boolean existing = u != null;
        if (u == null) {
            tempPassword = policy.random();
            u = new AppUser();
            u.setPhone(phone);
            u.setDisplayName(req.displayName().trim());
            u.setEmail(req.email());
            u.setPasswordHash(encoder.encode(tempPassword));
            u.setPasswordAlgo("bcrypt");
            u.setStatus("active");
            u.setEnabled(true);
            u.setTokenVersion(0);
            u.setMustChangePassword(true);
            u.setTempPasswordExpiresAt(LocalDateTime.now().plusDays(7));
            u.setPasswordUpdatedAt(LocalDateTime.now());
            u.setFailedCount(0);
            u.setLastOrgId(CurrentUser.orgId());
            u.setCreatedAt(LocalDateTime.now());
            users.insert(u);
            // 初始密码进历史，避免首登改密时复用它
            PasswordHistory h = new PasswordHistory();
            h.setUserId(u.getId());
            h.setPasswordHash(u.getPasswordHash());
            h.setCreatedAt(LocalDateTime.now());
            insertHistoryQuietly(h);
        } else if (req.displayName() != null && !req.displayName().isBlank()) {
            users.update(null, Wrappers.<AppUser>lambdaUpdate().eq(AppUser::getId, u.getId())
                    .set(AppUser::getDisplayName, req.displayName().trim()));
        }
        UserTenant t = members.selectOne(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, CurrentUser.orgId())
                .eq(UserTenant::getUserId, u.getId()).last("LIMIT 1"));
        if (t != null && "removed".equals(t.getStatus())) {
            members.update(null, Wrappers.<UserTenant>lambdaUpdate()
                    .eq(UserTenant::getId, t.getId())
                    .set(UserTenant::getStatus, "active")
                    .set(UserTenant::getRole, "tenant_admin".equals(userType) ? "admin" : "user")
                    .set(UserTenant::getUserType, userType)
                    .set(UserTenant::getRoleId, roleId)
                    .set(UserTenant::getRemovedAt, null)
                    .set(UserTenant::getJoinedAt, LocalDateTime.now())
                    .set(UserTenant::getUpdatedBy, CurrentUser.actor())
                    .set(UserTenant::getUpdatedAt, LocalDateTime.now())
                    .setSql("version = version + 1"));
        } else {
            t = new UserTenant();
            t.setOrgId(CurrentUser.orgId());
            t.setUserId(u.getId());
            t.setRole("tenant_admin".equals(userType) ? "admin" : "user");
            t.setUserType(userType);
            t.setRoleId(roleId);
            t.setStatus("active");
            t.setEmployeeNo(req.employeeNo());
            t.setTitle(req.title());
            t.setJoinedAt(LocalDateTime.now());
            t.setVersion(0);
            t.setCreatedBy(CurrentUser.actor());
            t.setUpdatedBy(CurrentUser.actor());
            t.setCreatedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            members.insert(t);
        }
        audit.record("user_tenant", t.getId(), "create", null,
                Map.of("phone", Hashes.maskPhone(phone), "userType", userType,
                        "roleId", String.valueOf(roleId)));
        return new UserDtos.CreateResp(t.getId(), u.getId(), Hashes.maskPhone(phone), tempPassword, existing);
    }

    /** 批量导入：整批共用一个临时密码，失败行返回原因，不影响其他行。 */
    public UserDtos.ImportResp importCsv(MultipartFile file) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw BizException.validation("文件读取失败，请使用 UTF-8 编码的 CSV");
        }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        String[] lines = content.split("\r?\n");
        if (lines.length < 2) throw BizException.validation("CSV 内容为空或缺少表头");
        String tempPassword = policy.random();
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",", -1);
            try {
                if (c.length < 3) throw BizException.validation("列数不足，需要 手机号,姓名,角色");
                createWithPassword(c[0].trim(), c[1].trim(), c[2].trim(),
                        c.length > 3 ? c[3].trim() : null, c.length > 4 ? c[4].trim() : null, tempPassword);
                success++;
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("line", i + 1);
                err.put("phone", c.length > 0 ? Hashes.maskPhone(c[0].trim()) : "");
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }
        return new UserDtos.ImportResp(success, errors.size(), errors, success > 0 ? tempPassword : null);
    }

    private void createWithPassword(String phone, String displayName, String role,
                                    String employeeNo, String title, String tempPassword) {
        String userType = resolveUserType(null, role);
        Long roleId = "tenant_admin".equals(userType) ? null : resolveRoleId(null, null);
        if (!phone.matches("^1\\d{10}$")) throw BizException.validation("手机号格式不正确");
        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone).last("LIMIT 1"));
        if (u == null) {
            u = new AppUser();
            u.setPhone(phone);
            u.setDisplayName(displayName);
            u.setPasswordHash(encoder.encode(tempPassword));
            u.setPasswordAlgo("bcrypt");
            u.setStatus("active");
            u.setEnabled(true);
            u.setTokenVersion(0);
            u.setMustChangePassword(true);
            u.setTempPasswordExpiresAt(LocalDateTime.now().plusDays(7));
            u.setPasswordUpdatedAt(LocalDateTime.now());
            u.setFailedCount(0);
            u.setLastOrgId(CurrentUser.orgId());
            u.setCreatedAt(LocalDateTime.now());
            users.insert(u);
        }
        Long n = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, CurrentUser.orgId())
                .eq(UserTenant::getUserId, u.getId()).eq(UserTenant::getStatus, "active"));
        if (n != null && n > 0) throw BizException.duplicate("该手机号已是本企业成员");
        UserTenant t = new UserTenant();
        t.setOrgId(CurrentUser.orgId());
        t.setUserId(u.getId());
        t.setRole("tenant_admin".equals(userType) ? "admin" : "user");
        t.setUserType(userType);
        t.setRoleId(roleId);
        t.setStatus("active");
        t.setEmployeeNo(employeeNo);
        t.setTitle(title);
        t.setJoinedAt(LocalDateTime.now());
        t.setVersion(0);
        t.setCreatedBy(CurrentUser.actor());
        t.setUpdatedBy(CurrentUser.actor());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        members.insert(t);
    }

    /* ==================== 修改 ==================== */

    @Transactional
    public void update(Long membershipId, UserDtos.UpdateReq req) {
        UserTenant t = require(membershipId);
        Long me = CurrentUser.userIdOrNull();
        boolean identityChanged = req.userType() != null || req.roleId() != null
                || (req.role() != null && !req.role().equals(t.getRole()));
        String targetType = req.userType() != null || req.role() != null
                ? resolveUserType(req.userType(), req.role()) : userTypeOf(t);
        Long targetRoleId = "tenant_admin".equals(targetType) ? null
                : resolveRoleId(req.roleId(), t.getRoleId());
        if (identityChanged) {
            if (t.getUserId().equals(me)) throw BizException.selfOp("不能修改自己的类型或角色");
            if ("tenant_admin".equals(userTypeOf(t)) && !"tenant_admin".equals(targetType) && adminCount() <= 1) {
                throw new BizException(ErrorCode.MEMBER_LAST_ADMIN, "至少保留一名管理员");
            }
        }
        var upd = Wrappers.<UserTenant>lambdaUpdate().eq(UserTenant::getId, t.getId());
        if (identityChanged) {
            upd.set(UserTenant::getUserType, targetType)
                    .set(UserTenant::getRole, "tenant_admin".equals(targetType) ? "admin" : "user")
                    .set(UserTenant::getRoleId, targetRoleId);
        }
        if (req.employeeNo() != null) upd.set(UserTenant::getEmployeeNo, req.employeeNo());
        if (req.title() != null) upd.set(UserTenant::getTitle, req.title());
        upd.set(UserTenant::getUpdatedBy, CurrentUser.actor())
                .set(UserTenant::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1");
        if (req.version() != null) upd.eq(UserTenant::getVersion, req.version());
        int rows = members.update(null, upd);
        if (rows == 0) {
            UserTenant latest = members.selectById(t.getId());
            throw BizException.conflict("成员信息已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? t.getVersion() : latest.getVersion()));
        }
        var uu = Wrappers.<AppUser>lambdaUpdate().eq(AppUser::getId, t.getUserId());
        boolean touchUser = false;
        if (req.displayName() != null && !req.displayName().isBlank()) {
            uu.set(AppUser::getDisplayName, req.displayName().trim());
            touchUser = true;
        }
        if (req.email() != null) {
            uu.set(AppUser::getEmail, req.email());
            touchUser = true;
        }
        if (touchUser) users.update(null, uu);
        if (identityChanged) bumpToken(t.getUserId());
        state.invalidateMember(t.getOrgId(), t.getUserId());
        audit.record("user_tenant", t.getId(), "update",
                Map.of("userType", userTypeOf(t), "roleId", String.valueOf(t.getRoleId())),
                Map.of("userType", targetType, "roleId", String.valueOf(targetRoleId)));
    }

    @Transactional
    public void disable(Long membershipId, boolean disabled) {
        UserTenant t = require(membershipId);
        if (disabled && t.getUserId().equals(CurrentUser.userIdOrNull())) {
            throw BizException.selfOp("不能停用自己");
        }
        if (disabled && "admin".equals(t.getRole()) && adminCount() <= 1) {
            throw new BizException(ErrorCode.MEMBER_LAST_ADMIN, "至少保留一名管理员");
        }
        members.update(null, Wrappers.<UserTenant>lambdaUpdate()
                .eq(UserTenant::getId, t.getId())
                .set(UserTenant::getStatus, disabled ? "disabled" : "active")
                .set(UserTenant::getUpdatedBy, CurrentUser.actor())
                .set(UserTenant::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        bumpToken(t.getUserId());
        state.invalidateMember(t.getOrgId(), t.getUserId());
        audit.record("user_tenant", t.getId(), disabled ? "disable" : "enable",
                java.util.Map.of("status", disabled ? "active" : "disabled"),
                java.util.Map.of("status", disabled ? "disabled" : "active"));
    }

    @Transactional
    public void remove(Long membershipId) {
        UserTenant t = require(membershipId);
        if (t.getUserId().equals(CurrentUser.userIdOrNull())) throw BizException.selfOp("不能移出自己");
        if ("admin".equals(t.getRole()) && adminCount() <= 1) throw new BizException(ErrorCode.MEMBER_LAST_ADMIN, "至少保留一名管理员");
        members.update(null, Wrappers.<UserTenant>lambdaUpdate()
                .eq(UserTenant::getId, t.getId())
                .set(UserTenant::getStatus, "removed")
                .set(UserTenant::getRemovedAt, LocalDateTime.now())
                .set(UserTenant::getUpdatedBy, CurrentUser.actor())
                .set(UserTenant::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        bumpToken(t.getUserId());
        state.invalidateMember(t.getOrgId(), t.getUserId());
        audit.record("user_tenant", t.getId(), "remove", null, null);
    }

    @Transactional
    public Map<String, Object> resetPassword(Long membershipId) {
        UserTenant t = require(membershipId);
        String temp = policy.random();
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, t.getUserId())
                .set(AppUser::getPasswordHash, encoder.encode(temp))
                .set(AppUser::getPasswordUpdatedAt, LocalDateTime.now())
                .set(AppUser::getMustChangePassword, true)
                .set(AppUser::getTempPasswordExpiresAt, LocalDateTime.now().plusDays(7))
                .set(AppUser::getStatus, "active")
                .set(AppUser::getFailedCount, 0)
                .set(AppUser::getLockedUntil, null)
                .setSql("token_version = token_version + 1"));
        PasswordHistory h = new PasswordHistory();
        h.setUserId(t.getUserId());
        h.setPasswordHash(users.selectById(t.getUserId()).getPasswordHash());
        h.setCreatedAt(LocalDateTime.now());
        insertHistoryQuietly(h);
        state.invalidate(t.getUserId());
        audit.record("user_tenant", t.getId(), "reset-password", null, null);
        return Map.of("tempPassword", temp, "expiresInDays", 7);
    }

    @Transactional
    public void unlock(Long membershipId) {
        UserTenant t = require(membershipId);
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, t.getUserId())
                .set(AppUser::getFailedCount, 0)
                .set(AppUser::getLockedUntil, null));
        state.invalidate(t.getUserId());
        audit.record("user_tenant", t.getId(), "unlock", null, null);
    }

    /** 离职交接：把该成员在本企业创建的业务数据转给接手人。 */
    @Transactional
    public Map<String, Object> handover(Long membershipId, Long toMembershipId) {
        UserTenant from = require(membershipId);
        UserTenant to = require(toMembershipId);
        AppUser fromUser = users.selectById(from.getUserId());
        AppUser toUser = users.selectById(to.getUserId());
        if (fromUser == null || toUser == null) throw BizException.notFound("账号不存在");
        String fromActor = fromUser.getPhone() == null ? fromUser.getUsername() : fromUser.getPhone();
        String toActor = toUser.getPhone() == null ? toUser.getUsername() : toUser.getPhone();
        int total = 0;
        Map<String, Integer> detail = new LinkedHashMap<>();
        for (String table : HANDOVER_TABLES) {
            try {
                int n = jdbc.update("update " + table + " set created_by = ? where org_id = ? and created_by = ?",
                        toActor, CurrentUser.orgId(), fromActor);
                if (n > 0) {
                    detail.put(table, n);
                    total += n;
                }
            } catch (Exception e) {
                // 个别表可能没有 created_by 列（不同版本迁移差异），跳过不阻断整体交接
                detail.put(table, -1);
            }
        }
        audit.record("user_tenant", from.getId(), "handover",
                Map.of("from", Hashes.maskPhone(fromActor)), Map.of("to", Hashes.maskPhone(toActor), "rows", total));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", total);
        out.put("detail", detail);
        return out;
    }

    /* ==================== 导出 ==================== */

    public byte[] exportCsv() {
        List<UserDtos.MemberView> list = list(null, null, null, null);
        List<List<Object>> rows = new ArrayList<>();
        for (UserDtos.MemberView m : list) {
            rows.add(List.of(m.phone() == null ? "" : m.phone(),
                    m.displayName() == null ? "" : m.displayName(),
                    "tenant_admin".equals(m.userType()) ? "租户管理员" : m.roleName(),
                    switch (m.status()) {
                        case "active" -> "正常";
                        case "disabled" -> "停用";
                        case "pending" -> "待确认";
                        default -> m.status();
                    },
                    m.employeeNo() == null ? "" : m.employeeNo(),
                    m.title() == null ? "" : m.title(),
                    m.lastLoginAt() == null ? "从未登录" : m.lastLoginAt()));
        }
        return Csv.build(List.of("手机号", "姓名", "角色", "状态", "工号", "职位", "最近登录"), rows);
    }

    /**
     * 导入模板：列与 {@link #importCsv} 解析顺序一致（手机号,姓名,角色,工号,职位）。
     * 示例行仅示范写法，导入前需替换为真实数据。
     */
    public byte[] importTemplateCsv() {
        return Csv.build(List.of("手机号", "姓名", "角色", "工号", "职位"), List.of(
                List.of("13900000001", "张三", "运营", "EMP001", "能源管理员"),
                List.of("13900000002", "李四", "运营", "EMP002", "碳核算专员")));
    }

    /** 席位上限来自租户套餐；用尽时拒绝新增成员。 */
    private void assertSeatAvailable() {
        Long orgId = CurrentUser.orgId();
        Integer limit;
        try {
            limit = jdbc.queryForObject("select seat_limit from org where id = ?", Integer.class, orgId);
        } catch (Exception e) {
            return;
        }
        if (limit == null) return;
        Integer used = jdbc.queryForObject(
                "select count(*) from user_tenant where org_id = ? and status <> 'removed'", Integer.class, orgId);
        if (used != null && used >= limit) {
            throw new BizException(ErrorCode.SEAT_LIMIT,
                    "席位已用尽（" + used + "/" + limit + "），请联系平台管理员扩容",
                    Map.of("used", used, "limit", limit));
        }
    }

    /* ==================== 内部 ==================== */

    private Map<Long, String> lastLoginMap() {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("select user_id, max(at) as last_at from login_log where org_id = ? and result = 'ok' group by user_id",
                rs -> {
                    out.put(rs.getLong("user_id"),
                            rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toLocalDateTime().format(TS));
                }, CurrentUser.orgId());
        return out;
    }

    private long adminCount() {
        Long n = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, CurrentUser.orgId())
                .eq(UserTenant::getRole, "admin")
                .eq(UserTenant::getStatus, "active"));
        return n == null ? 0 : n;
    }

    private void bumpToken(Long userId) {
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, userId).setSql("token_version = token_version + 1"));
    }

    /** 同一密码重复时唯一键会拒绝，这里忽略即可。 */
    private void insertHistoryQuietly(PasswordHistory h) {
        if (h.getPasswordHash() == null) return;
        try {
            passwordHistory.insert(h);
        } catch (Exception ignored) {
        }
    }

    /** 账号导入/新增允许的角色写法：中文角色名 + 旧版英文值，统一映射到两级成员类型。 */
    private static final Set<String> ADMIN_ROLE_ALIASES =
            Set.of("admin", "tenant_admin", "管理员", "租户管理员");
    private static final Set<String> USER_ROLE_ALIASES =
            Set.of("user", "运营", "普通用户", "filler", "reviewer", "approver", "viewer");

    /** 新入参优先 userType；旧入参按 admin / 其他 映射到三级身份。 */
    private String resolveUserType(String userType, String role) {
        if ("tenant_admin".equals(userType) || "user".equals(userType)) return userType;
        if (role != null && !role.isBlank()) {
            String r = role.trim();
            if (ADMIN_ROLE_ALIASES.contains(r)) return "tenant_admin";
            if (USER_ROLE_ALIASES.contains(r)) return "user";
            throw BizException.validation("角色不合法，可选：运营 / 租户管理员");
        }
        return "user";
    }

    private Long resolveRoleId(Long roleId, Long fallback) {
        if (roleId != null) {
            roles.detail(roleId); // 校验同租户存在
            return roleId;
        }
        if (fallback != null) return fallback;
        Long def = roles.defaultRoleId(CurrentUser.orgId());
        if (def == null) throw BizException.validation("请先创建角色");
        return def;
    }

    private static String userTypeOf(UserTenant t) {
        if (t.getUserType() != null) return t.getUserType();
        return "admin".equals(t.getRole()) ? "tenant_admin" : "user";
    }

    /** 跨企业访问返回 404，不暴露存在性。 */
    private UserTenant require(Long membershipId) {
        UserTenant t = members.selectById(membershipId);
        if (t == null || "removed".equals(t.getStatus())) throw BizException.notFound("成员不存在");
        if (!t.getOrgId().equals(CurrentUser.orgId())) throw BizException.notFound("成员不存在");
        return t;
    }
}
