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

    /**
     * 批量导入：整批共用一个临时密码，失败行返回原因，不影响其他行。
     * <p>
     * 列定位按<b>表头名</b>而非固定列序：表头需包含「手机号」「姓名」，其余列
     * （用户类型 / 角色 / 工号 / 职位）可选；列顺序可调整、多余的列（如导出文件带的
     * 「状态」「最近登录」）会被忽略，因此与导出文件共用同一套表头名。
     * 兼容旧 5 列模板（手机号,姓名,角色,工号,职位）：没有「用户类型」列时按普通用户处理。
     */
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
        ImportColumns cols = ImportColumns.parse(lines[0]);
        String tempPassword = policy.random();
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",", -1);
            String phone = cols.cell(c, cols.phone);
            try {
                String displayName = cols.cell(c, cols.name);
                if (displayName.isEmpty()) throw BizException.validation("姓名不能为空");
                createWithPassword(phone, displayName, cols.cell(c, cols.userType), cols.cell(c, cols.role),
                        cols.cell(c, cols.employeeNo), cols.cell(c, cols.title), tempPassword);
                success++;
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("line", i + 1);
                err.put("phone", Hashes.maskPhone(phone));
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }
        return new UserDtos.ImportResp(success, errors.size(), errors, success > 0 ? tempPassword : null);
    }

    /**
     * 导入表头映射：把「表头名 → 列下标」解析出来，缺失的列下标保持 -1（读取时视为空串）。
     * 表头名允许前后空格；同一表头重复出现时以第一次出现为准。
     */
    private static final class ImportColumns {

        private static final String H_PHONE = "手机号";
        private static final String H_NAME = "姓名";
        private static final String H_USER_TYPE = "用户类型";
        private static final String H_ROLE = "角色";
        private static final String H_EMPLOYEE_NO = "工号";
        private static final String H_TITLE = "职位";

        private int phone = -1;
        private int name = -1;
        private int userType = -1;
        private int role = -1;
        private int employeeNo = -1;
        private int title = -1;

        /**
         * 解析表头行；缺少必需列直接抛出可读错误。
         *
         * @param headerLine CSV 第一行（可能带 BOM）
         * @return 列下标映射
         */
        private static ImportColumns parse(String headerLine) {
            String[] cells = headerLine.replace("\uFEFF", "").split(",", -1);
            ImportColumns c = new ImportColumns();
            for (int i = 0; i < cells.length; i++) {
                String h = cells[i] == null ? "" : cells[i].trim();
                switch (h) {
                    case H_PHONE -> c.phone = c.phone < 0 ? i : c.phone;
                    case H_NAME -> c.name = c.name < 0 ? i : c.name;
                    case H_USER_TYPE -> c.userType = c.userType < 0 ? i : c.userType;
                    case H_ROLE -> c.role = c.role < 0 ? i : c.role;
                    case H_EMPLOYEE_NO -> c.employeeNo = c.employeeNo < 0 ? i : c.employeeNo;
                    case H_TITLE -> c.title = c.title < 0 ? i : c.title;
                    default -> {
                        // 未知列（状态 / 最近登录 / 备注等）忽略，保证导出文件可回导
                    }
                }
            }
            if (c.phone < 0) throw BizException.validation("表头缺少：" + H_PHONE);
            if (c.name < 0) throw BizException.validation("表头缺少：" + H_NAME);
            // 旧 5 列模板（手机号,姓名,角色,工号,职位）没有「用户类型」列：
            // 保留历史语义「第 3 列是角色」，避免老模板整体失效
            if (c.userType < 0 && c.role < 0 && cells.length > 2) c.role = 2;
            return c;
        }

        /**
         * 读取单元格，越界或缺失列返回空串（不返回 null，便于下游 isBlank 判断）。
         *
         * @param row CSV 行单元格数组
         * @param idx 列下标，-1 表示该列不存在
         * @return 去空格的单元格文本
         */
        private String cell(String[] row, int idx) {
            if (row == null || idx < 0 || idx >= row.length) return "";
            String v = row[idx];
            return v == null ? "" : v.trim();
        }
    }

    /**
     * 按导入行的原始单元格创建成员：用户类型写在「用户类型」列，角色写在「角色」列。
     *
     * @param phone        手机号（必填，1 开头 11 位）
     * @param displayName  姓名（必填）
     * @param rawUserType  用户类型原值（普通用户 / 租户管理员 / user / tenant_admin / 空）
     * @param rawRole      角色名原值（可空；必须是角色管理中已配置的启用角色）
     * @param employeeNo   工号（可空）
     * @param title        职位（可空）
     * @param tempPassword 整批共用的临时密码
     */
    private void createWithPassword(String phone, String displayName, String rawUserType, String rawRole,
                                    String employeeNo, String title, String tempPassword) {
        String userType = parseImportUserType(rawUserType);
        Long roleId;
        if ("tenant_admin".equals(userType)) {
            // 管理员不挂业务角色：角色列宽容忽略，roleId 必须为 null
            roleId = null;
        } else if (rawRole == null || rawRole.isBlank()) {
            roleId = resolveRoleId(null, null);
        } else {
            roleId = requireRoleIdByName(rawRole.trim());
        }
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

    /**
     * 导出成员列表：表头名与导入模板一致（多出「状态」「最近登录」两列），
     * {@link #importCsv} 按表头名定位列并忽略多余列，因此列结构与导入模板兼容。
     * <p>
     * 「手机号」列输出<b>完整号码</b>（导出接口仅 ADMIN 可调用），导出文件可直接回导：
     * 已存在的号码会按「该手机号已是本企业成员」逐行失败，其余行正常导入。
     * 注意列表页（{@link #list}）仍输出脱敏手机号，前端依赖该字段，两者取值来源不同。
     * 租户管理员不挂业务角色，其「用户类型」为「租户管理员」、「角色」列留空；
     * 普通用户的「角色」列输出角色名，取不到名字时同样留空（不输出占位符）。
     */
    public byte[] exportCsv() {
        List<UserDtos.MemberView> list = list(null, null, null, null);
        Map<Long, String> realPhones = realPhoneMap(list);
        List<List<Object>> rows = new ArrayList<>();
        for (UserDtos.MemberView m : list) {
            boolean admin = "tenant_admin".equals(m.userType());
            String roleName = m.roleName() == null || "—".equals(m.roleName()) ? "" : m.roleName();
            rows.add(List.of(realPhones.getOrDefault(m.userId(), ""),
                    m.displayName() == null ? "" : m.displayName(),
                    admin ? "租户管理员" : "普通用户",
                    admin ? "" : roleName,
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
        return Csv.build(List.of("手机号", "姓名", "用户类型", "角色", "状态", "工号", "职位", "最近登录"), rows);
    }

    /**
     * 回查导出行的完整手机号：{@link #list} 返回的 {@code MemberView.phone} 是脱敏值
     * （如 {@code 138****0001}），不能写进导出文件，这里按 userId 批量取
     * {@code app_user.phone} 的原始号码。
     * <p>
     * 注意 MyBatis-Plus 的 {@code selectBatchIds} 传入空集合会拼出非法 SQL，故空列表直接短路。
     *
     * @param list 成员视图列表（其中手机号为脱敏值）
     * @return userId → 完整手机号；未查到或号码为空的 userId 不出现在 Map 中，调用方回退空串
     */
    private Map<Long, String> realPhoneMap(List<UserDtos.MemberView> list) {
        Map<Long, String> out = new HashMap<>();
        if (list.isEmpty()) return out;
        List<Long> userIds = list.stream()
                .map(UserDtos.MemberView::userId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (userIds.isEmpty()) return out;
        for (AppUser u : users.selectBatchIds(userIds)) {
            if (u != null && u.getId() != null && u.getPhone() != null) out.put(u.getId(), u.getPhone());
        }
        return out;
    }

    /**
     * 导入模板：表头名与 {@link #importCsv} 的列名解析完全一致
     * （手机号,姓名,用户类型,角色,工号,职位），列顺序可调整，表头名必须保留。
     * 示例行仅示范写法（第二行演示「角色」可留空），导入前需替换为真实数据。
     */
    public byte[] importTemplateCsv() {
        return Csv.build(List.of("手机号", "姓名", "用户类型", "角色", "工号", "职位"), List.of(
                List.of("13900000001", "张三", "普通用户", "运营", "EMP001", "能源管理员"),
                List.of("13900000002", "李四", "普通用户", "", "EMP002", "碳核算专员")));
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

    /**
     * 账号新增/修改接口允许的角色写法：中文角色名 + 旧版英文值，统一映射到两级成员类型。
     * <p>
     * 仅供 {@link #resolveUserType} 为旧客户端兜底；<b>导入路径不再使用</b>
     * （导入按「用户类型」列判定类型，「角色」列必须是角色管理里配置的真实角色）。
     */
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

    /**
     * 解析导入文件的「用户类型」列：留空按普通用户；只接受两种中文值（及对应英文值）。
     *
     * @param rawUserType 单元格原值，可为 null
     * @return {@code user} 或 {@code tenant_admin}
     */
    private static String parseImportUserType(String rawUserType) {
        String t = rawUserType == null ? "" : rawUserType.trim();
        if (t.isEmpty() || "普通用户".equals(t) || "user".equalsIgnoreCase(t)) return "user";
        if ("租户管理员".equals(t) || "管理员".equals(t)
                || "tenant_admin".equalsIgnoreCase(t) || "admin".equalsIgnoreCase(t)) {
            return "tenant_admin";
        }
        throw BizException.validation("用户类型只能填普通用户或租户管理员");
    }

    /**
     * 按角色名解析当前租户<b>启用中</b>的角色 ID。
     * <p>
     * 校验顺序是「先查表、查不到再给提示」：租户在「角色管理」里配置了什么名字，
     * 导入就可以用什么名字——即使角色名叫「管理员」「admin」，只要确实存在就照用，
     * 不按类型别名预先拦截（否则用户自建的同名角色会被误拒）。
     * <p>
     * 查不到时给两条不同的提示：
     * <ul>
     *   <li>值是 {@code 租户管理员} / {@code tenant_admin}：这是<b>用户类型</b>而不是角色，
     *       提示改填「用户类型」列；</li>
     *   <li>其它值：提示该角色未在角色管理中创建。</li>
     * </ul>
     *
     * @param roleName 角色名（非空，已 trim）
     * @return 角色 ID
     */
    private Long requireRoleIdByName(String roleName) {
        Long roleId = roles.idByName(roleName);
        if (roleId != null) return roleId;
        if ("租户管理员".equals(roleName) || "tenant_admin".equalsIgnoreCase(roleName)) {
            throw BizException.validation("角色「" + roleName + "」不存在；租户管理员请填在「用户类型」列");
        }
        throw BizException.validation("角色「" + roleName + "」不存在，请先在角色管理中创建");
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
