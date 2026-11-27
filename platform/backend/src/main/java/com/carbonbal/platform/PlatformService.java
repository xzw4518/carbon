package com.carbonbal.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.AppUser;
import com.carbonbal.auth.AppUserMapper;
import com.carbonbal.auth.AuthStateService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.auth.PasswordPolicy;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Hashes;
import com.carbonbal.identity.RoleService;
import com.carbonbal.identity.UserTenant;
import com.carbonbal.identity.UserTenantMapper;
import com.carbonbal.org.Org;
import com.carbonbal.org.OrgMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台端：租户开通、授权期、套餐与席位。
 * <p>
 * 租户状态：pending（未生效）/ active（正常）/ suspended（停用）/ expired（到期）。
 * 到期或停用后，该租户所有账号无法登录、已登录会话在 30 秒内失效；超级管理员不受此限制。
 */
@Service
public class PlatformService {

    private static final List<String> STATUSES = List.of("pending", "active", "suspended", "expired");

    /** 租户列表/概览共用列：成员、设备、报告、租户管理员。 */
    private static final String TENANT_SELECT = """
            select o.id, o.name, o.code, o.tenant_code, o.status, o.plan, o.seat_limit,
                   o.expire_at, o.license_start_at, o.created_at,
                   (select count(*) from user_tenant t where t.org_id = o.id and t.status <> 'removed') as members,
                   (select count(*) from device d where d.org_id = o.id and d.disabled = 0) as devices,
                   (select count(*) from report r where r.org_id = o.id and r.disabled = 0) as reports,
                   (select u.display_name from user_tenant t join app_user u on u.id = t.user_id
                      where t.org_id = o.id and t.role = 'admin' and t.status = 'active' order by t.id limit 1) as admin_name,
                   (select u.phone from user_tenant t join app_user u on u.id = t.user_id
                      where t.org_id = o.id and t.role = 'admin' and t.status = 'active' order by t.id limit 1) as admin_phone
            from org o
            """;

    private final OrgMapper orgs;
    private final AppUserMapper users;
    private final UserTenantMapper members;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuthStateService state;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RoleService roleService;

    public PlatformService(OrgMapper orgs, AppUserMapper users, UserTenantMapper members,
                           PasswordEncoder encoder, PasswordPolicy policy,
                           AuthStateService state, JdbcTemplate jdbc, AuditService audit,
                           RoleService roleService) {
        this.orgs = orgs;
        this.users = users;
        this.members = members;
        this.encoder = encoder;
        this.policy = policy;
        this.state = state;
        this.jdbc = jdbc;
        this.audit = audit;
        this.roleService = roleService;
    }

    /* ==================== 查询 ==================== */

    public Map<String, Object> tenants(int page, int size, String keyword, String status, String expiring) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 200);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (o.name like ? or o.code like ? or o.tenant_code like ?)");
            String k = "%" + keyword.trim() + "%";
            args.add(k);
            args.add(k);
            args.add(k);
        }
        String st = status == null ? "" : status.trim();
        switch (st) {
            case "pending" -> where.append(" and o.status = 'pending'");
            case "suspended" -> where.append(" and o.status in ('suspended','disabled')");
            case "expired" -> where.append(" and (o.status = 'expired'")
                    .append(" or (o.status = 'active' and o.expire_at is not null and o.expire_at < now()))");
            case "active" -> where.append(" and o.status = 'active'")
                    .append(" and (o.expire_at is null or o.expire_at >= now())");
            default -> { }
        }
        if ("30".equals(expiring)) {
            where.append(" and o.status = 'active' and o.expire_at is not null")
                    .append(" and o.expire_at between now() and timestampadd(day, 30, now())");
        }

        Integer total = jdbc.queryForObject("select count(*) from org o" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((p - 1) * sz);
        List<Map<String, Object>> rows = jdbc.query(TENANT_SELECT
                        + where + " order by o.id desc limit ? offset ?",
                (rs, i) -> row(rs), pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "rows", rows, "page", p, "size", sz);
    }

    public Map<String, Object> detail(Long id) {
        Org o = require(id);
        Map<String, Object> m = baseView(o);
        m.put("devices", jdbc.queryForObject(
                "select count(*) from device where org_id = ? and disabled = 0", Integer.class, id));
        m.put("reports", jdbc.queryForObject(
                "select count(*) from report where org_id = ? and disabled = 0", Integer.class, id));
        m.put("createdAt", o.getCreatedAt());
        return m;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenants", count("select count(*) from org"));
        m.put("activeTenants", count("select count(*) from org where status = 'active' "
                + "and (expire_at is null or expire_at >= now())"));
        m.put("pendingTenants", count("select count(*) from org where status = 'pending'"));
        m.put("expiring30", count("select count(*) from org where status = 'active' and expire_at is not null "
                + "and expire_at between now() and timestampadd(day, 30, now())"));
        m.put("expiredTenants", count("select count(*) from org where status = 'expired' "
                + "or (status = 'active' and expire_at is not null and expire_at < now())"));
        m.put("members", count("select count(*) from user_tenant where status <> 'removed'"));
        m.put("accounts", count("select count(*) from app_user where deleted_at is null"));
        // 席位只统计设置了上限的租户，避免“使用 6 / 总 3”这类误导
        m.put("seatUsed", count("select count(*) from user_tenant t join org o on o.id = t.org_id "
                + "where t.status <> 'removed' and o.seat_limit is not null"));
        Integer seats = jdbc.queryForObject("select coalesce(sum(seat_limit),0) from org", Integer.class);
        m.put("seatTotal", seats == null ? 0 : seats);
        m.put("devices", count("select count(*) from device where disabled = 0"));
        m.put("reports", count("select count(*) from report where disabled = 0"));
        return m;
    }

    /** 平台概览：KPI + 30 日内到期（含已到期）预警 + 最近平台操作。 */
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stats", stats());
        m.put("expiring", expiring(8));
        m.put("recent", audit(1, 8, null, null, null, null).get("rows"));
        return m;
    }

    private List<Map<String, Object>> expiring(int limit) {
        List<Object> args = new ArrayList<>();
        args.add(limit);
        return jdbc.query(TENANT_SELECT + """
                 where o.status = 'expired'
                    or (o.status = 'active' and o.expire_at is not null
                        and o.expire_at <= timestampadd(day, 30, now()))
                 order by o.expire_at asc limit ?
                """, (rs, i) -> row(rs), args.toArray());
    }

    /**
     * 平台审计：跨租户的开通/设置/续期/停用/恢复/代管进入。
     * 平台操作的审计归属固定为 org_id = 0，不进入任何租户的审计列表。
     */
    public Map<String, Object> audit(int page, int size, String action, String keyword,
                                     String from, String to) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        StringBuilder where = new StringBuilder(" where a.org_id = 0");
        List<Object> args = new ArrayList<>();

        String act = action == null ? "" : action.trim();
        if (!act.isEmpty()) {
            where.append(" and a.action = ?");
            args.add(act);
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            String like = "%" + kw + "%";
            List<Long> ids = jdbc.queryForList(
                    "select id from org where name like ? or code like ? or tenant_code like ?",
                    Long.class, like, like, like);
            StringBuilder in = new StringBuilder();
            if (!ids.isEmpty()) {
                in.append(" or a.entity_id in (");
                for (int i = 0; i < ids.size(); i++) in.append(i == 0 ? "?" : ",?");
                in.append(")");
            }
            where.append(" and (a.actor like ? or a.action_label like ? or a.entity_id = ?")
                    .append(in).append(")");
            args.add(like);
            args.add(like);
            args.add(kw);
            if (!ids.isEmpty()) args.addAll(ids);
        }
        LocalDate begin = dateOrNull(from);
        if (begin != null) {
            where.append(" and a.logged_at >= ?");
            args.add(begin.atStartOfDay());
        }
        LocalDate end = dateOrNull(to);
        if (end != null) {
            where.append(" and a.logged_at < ?");
            args.add(end.plusDays(1).atStartOfDay());
        }

        Integer total = jdbc.queryForObject("select count(*) from audit_log a" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbc.query("""
                select a.id, a.logged_at, a.actor, a.actor_id, a.action, a.action_label,
                       a.entity, a.entity_id, a.before_json, a.after_json
                from audit_log a
                """ + where + " order by a.id desc limit ? offset ?", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            java.sql.Timestamp at = rs.getTimestamp("logged_at");
            row.put("loggedAt", at == null ? null : at.toLocalDateTime().toString());
            row.put("actor", rs.getString("actor"));
            row.put("actorId", rs.getObject("actor_id"));
            row.put("action", rs.getString("action"));
            row.put("actionLabel", platformActionLabel(rs.getString("action")));
            row.put("entity", rs.getString("entity"));
            row.put("entityLabel", AuditService.entityLabel(rs.getString("entity")));
            row.put("entityId", rs.getString("entity_id"));
            row.put("beforeJson", rs.getString("before_json"));
            row.put("afterJson", rs.getString("after_json"));
            return row;
        }, pageArgs.toArray());

        // 目标租户名：审计表只存 id，名称在此解析，避免 SQL 里做字符串转数字
        List<Long> tenantIds = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!"tenant".equals(r.get("entity"))) continue;
            try {
                tenantIds.add(Long.parseLong(String.valueOf(r.get("entityId"))));
            } catch (NumberFormatException ignored) { }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        if (!tenantIds.isEmpty()) {
            for (Org o : orgs.selectBatchIds(tenantIds)) names.put(o.getId(), o.getName());
        }
        for (Map<String, Object> r : rows) {
            if (!"tenant".equals(r.get("entity"))) continue;
            try {
                r.put("targetName", names.get(Long.parseLong(String.valueOf(r.get("entityId")))));
            } catch (NumberFormatException ignored) { }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total == null ? 0 : total);
        m.put("rows", rows);
        m.put("page", p);
        m.put("size", s);
        return m;
    }

    /** 平台审计的动作口径：同一 action 在平台语境下叫法不同（create → 开通）。 */
    private static String platformActionLabel(String action) {
        if (action == null) return null;
        return switch (action) {
            case "create" -> "开通";
            case "update" -> "设置";
            case "enter" -> "进入租户";
            case "exit" -> "退出租户";
            default -> AuditService.actionLabel(action);
        };
    }

    /** 4 位大写字母数字，用于自动企业编码。 */
    private static String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 日期筛选：空值不过滤；格式错误明确报错，避免静默忽略后返回全量。 */
    private static LocalDate dateOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            String s = v.trim();
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            throw BizException.validation("日期格式应为 YYYY-MM-DD");
        }
    }

    /** 兼容旧接口：按租户用量汇总。 */
    public List<Map<String, Object>> usage() {
        return jdbc.query("""
                select o.id, o.name, o.status, o.seat_limit,
                       (select count(*) from user_tenant t where t.org_id = o.id and t.status <> 'removed') as members,
                       (select count(*) from device d where d.org_id = o.id and d.disabled = 0) as devices,
                       (select count(*) from report r where r.org_id = o.id and r.disabled = 0) as reports
                from org o order by o.id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orgId", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("status", rs.getString("status"));
            m.put("seatLimit", rs.getObject("seat_limit"));
            m.put("members", rs.getInt("members"));
            m.put("devices", rs.getInt("devices"));
            m.put("reports", rs.getInt("reports"));
            return m;
        });
    }

    /* ==================== 开通 ==================== */

    @Transactional
    public Map<String, Object> createTenant(Map<String, Object> body) {
        String name = str(body, "name");
        String code = str(body, "code");
        String adminPhone = str(body, "adminPhone");
        String adminName = str(body, "adminName");
        if (name == null) throw BizException.validation("企业名称必填");
        if (adminPhone == null || !adminPhone.matches("^1\\d{10}$")) {
            throw BizException.validation("管理员手机号格式不正确");
        }
        // 未填企业编码时生成短码（ORG-xxxx），避免时间戳长串出现在列表与详情里
        for (int i = 0; code == null && i < 5; i++) {
            String candidate = "ORG-" + randomCode();
            if (orgs.selectCount(Wrappers.<Org>lambdaQuery().eq(Org::getCode, candidate)) == 0) code = candidate;
        }
        if (code == null) throw BizException.validation("企业编码生成失败，请手工填写");
        if (orgs.selectCount(Wrappers.<Org>lambdaQuery().eq(Org::getCode, code)) > 0) {
            throw BizException.duplicate("企业编码已存在", code);
        }
        String tenantCode = str(body, "tenantCode");
        if (tenantCode != null && orgs.selectCount(Wrappers.<Org>lambdaQuery()
                .eq(Org::getTenantCode, tenantCode)) > 0) {
            throw BizException.duplicate("租户短码已存在", tenantCode);
        }

        LocalDate today = LocalDate.now();
        LocalDate start = dateOf(body, "licenseStartAt", today);
        LocalDate end = dateOf(body, "licenseEndAt", start.plusYears(1).minusDays(1));
        assertLicense(start, end);

        Org o = new Org();
        o.setName(name);
        o.setCode(code);
        o.setStatus(start.isAfter(today) ? "pending" : "active");
        o.setPlan(str(body, "plan") == null ? "standard" : str(body, "plan"));
        o.setSeatLimit(intOf(body, "seatLimit", 20, 1, 10000));
        o.setLicenseStartAt(start);
        o.setExpireAt(end.atTime(23, 59, 59));
        o.setOrgLevel(body.get("parentOrgId") == null ? 1 : 2);
        o.setParentOrgId(body.get("parentOrgId") == null ? null : Long.parseLong(body.get("parentOrgId").toString()));
        o.setTenantCode(tenantCode);
        o.setTimezone("Asia/Shanghai");
        o.setCreatedAt(LocalDateTime.now());
        o.setVersion(0);
        orgs.insert(o);

        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, adminPhone).last("LIMIT 1"));
        String temp = null;
        if (u == null) {
            temp = policy.random();
            u = new AppUser();
            u.setPhone(adminPhone);
            u.setDisplayName(adminName == null ? name + " 管理员" : adminName);
            u.setPasswordHash(encoder.encode(temp));
            u.setPasswordAlgo("bcrypt");
            u.setStatus("active");
            u.setEnabled(true);
            u.setTokenVersion(0);
            u.setMustChangePassword(true);
            u.setTempPasswordExpiresAt(LocalDateTime.now().plusDays(7));
            u.setPasswordUpdatedAt(LocalDateTime.now());
            u.setFailedCount(0);
            u.setLastOrgId(o.getId());
            u.setCreatedAt(LocalDateTime.now());
            users.insert(u);
        } else if (Boolean.TRUE.equals(u.getPlatformAdmin())) {
            throw BizException.validation("该手机号是平台账号，不能作为租户管理员");
        }
        UserTenant t = new UserTenant();
        t.setOrgId(o.getId());
        t.setUserId(u.getId());
        t.setRole("admin");
        t.setUserType("tenant_admin");
        t.setStatus("active");
        t.setJoinedAt(LocalDateTime.now());
        t.setVersion(0);
        t.setCreatedBy(CurrentUser.actor());
        t.setUpdatedBy(CurrentUser.actor());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        members.insert(t);

        // 主数据骨架：默认设施，便于直接开始填报
        jdbc.update("insert into facility (org_id, name, code, parent_id, tree_level, sort_no, remark, disabled, version, created_by, updated_by, created_at, updated_at) "
                        + "values (?, ?, ?, null, 1, 1, null, 0, 0, ?, ?, now(), now())",
                o.getId(), "一号厂区", code + "-F1", CurrentUser.actor(), CurrentUser.actor());

        // 默认角色【运营】：新租户开箱可用
        roleService.ensureDefault(o.getId());
        audit.record("tenant", o.getId(), "create", null, baseView(o), AuditService.PLATFORM_ORG_ID);
        state.invalidateMember(o.getId(), u.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", o.getId());
        out.put("code", code);
        out.put("status", o.getStatus());
        out.put("licenseStartAt", start.toString());
        out.put("licenseEndAt", end.toString());
        out.put("adminPhone", Hashes.maskPhone(adminPhone));
        out.put("tempPassword", temp);
        out.put("existingAccount", temp == null);
        return out;
    }

    /* ==================== 修改 / 续期 / 状态 ==================== */

    @Transactional
    public Map<String, Object> updateTenant(Long id, Map<String, Object> body) {
        Org o = require(id);
        Map<String, Object> before = baseView(o);

        Org patch = new Org();
        String name = str(body, "name");
        if (name != null) patch.setName(name);
        String tenantCode = str(body, "tenantCode");
        if (tenantCode != null && !tenantCode.equals(o.getTenantCode())) {
            if (orgs.selectCount(Wrappers.<Org>lambdaQuery()
                    .eq(Org::getTenantCode, tenantCode).ne(Org::getId, id)) > 0) {
                throw BizException.duplicate("租户短码已存在", tenantCode);
            }
            patch.setTenantCode(tenantCode);
        }
        if (body.get("plan") != null) patch.setPlan(String.valueOf(body.get("plan")));
        if (body.get("seatLimit") != null) {
            int seats = intOf(body, "seatLimit", 20, 1, 10000);
            int used = count("select count(*) from user_tenant where org_id = " + id + " and status <> 'removed'");
            if (seats < used) throw new BizException(ErrorCode.SEAT_LIMIT,
                    "席位数不能小于当前成员数（已用 " + used + " 席）", Map.of("used", used));
            patch.setSeatLimit(seats);
        }
        LocalDate start = body.get("licenseStartAt") == null ? o.getLicenseStartAt()
                : dateOf(body, "licenseStartAt", o.getLicenseStartAt());
        LocalDate end = body.get("licenseEndAt") == null ? endDate(o)
                : dateOf(body, "licenseEndAt", endDate(o));
        if (body.get("licenseStartAt") != null || body.get("licenseEndAt") != null) {
            assertLicense(start, end);
            patch.setLicenseStartAt(start);
            patch.setExpireAt(end.atTime(23, 59, 59));
        }
        String status = str(body, "status");
        if (status != null) {
            if (!STATUSES.contains(status)) {
                throw BizException.validation("状态只能是 pending / active / suspended / expired");
            }
            patch.setStatus(status);
            if (!"active".equals(status)) patch.setSuspendedAt(LocalDateTime.now());
        }
        orgs.update(null, Wrappers.<Org>lambdaUpdate()
                .eq(Org::getId, id)
                .set(patch.getName() != null, Org::getName, patch.getName())
                .set(patch.getTenantCode() != null, Org::getTenantCode, patch.getTenantCode())
                .set(patch.getPlan() != null, Org::getPlan, patch.getPlan())
                .set(patch.getSeatLimit() != null, Org::getSeatLimit, patch.getSeatLimit())
                .set(patch.getLicenseStartAt() != null, Org::getLicenseStartAt, patch.getLicenseStartAt())
                .set(patch.getExpireAt() != null, Org::getExpireAt, patch.getExpireAt())
                .set(patch.getStatus() != null, Org::getStatus, patch.getStatus())
                .set(patch.getSuspendedAt() != null, Org::getSuspendedAt, patch.getSuspendedAt())
                .set(Org::getUpdatedBy, CurrentUser.actor())
                .set(Org::getUpdatedAt, LocalDateTime.now()));

        Org after = require(id);
        invalidateTenant(id);
        audit.record("tenant", id, "update", before, baseView(after), AuditService.PLATFORM_ORG_ID);
        return baseView(after);
    }

    /** 续期：默认在原到期日基础上顺延 12 个月；已过期则从今天重新起算。 */
    @Transactional
    public Map<String, Object> renew(Long id, Map<String, Object> body) {
        Org o = require(id);
        Map<String, Object> before = baseView(o);
        LocalDate today = LocalDate.now();
        LocalDate base = endDate(o);
        if (base.isBefore(today)) base = today.minusDays(1);
        LocalDate end;
        String endStr = str(body, "licenseEndAt");
        if (endStr != null) {
            // 非法日期交给 dateOf 统一转 422，避免 DateTimeParseException 变成 500
            end = dateOf(body, "licenseEndAt", base);
        } else {
            int months = intOf(body, "months", 12, 1, 120);
            end = base.plusMonths(months);
        }
        LocalDate start = o.getLicenseStartAt() == null ? today : o.getLicenseStartAt();
        assertLicense(start, end);
        String status = start.isAfter(today) ? "pending" : "active";
        orgs.update(null, Wrappers.<Org>lambdaUpdate()
                .eq(Org::getId, id)
                .set(Org::getLicenseStartAt, start)
                .set(Org::getExpireAt, end.atTime(23, 59, 59))
                .set(Org::getStatus, status)
                .set(Org::getSuspendedAt, null)
                .set(Org::getUpdatedBy, CurrentUser.actor())
                .set(Org::getUpdatedAt, LocalDateTime.now()));
        invalidateTenant(id);
        Org after = require(id);
        audit.record("tenant", id, "renew", before, baseView(after), AuditService.PLATFORM_ORG_ID);
        return baseView(after);
    }

    @Transactional
    public Map<String, Object> suspend(Long id) {
        Org o = require(id);
        Map<String, Object> before = baseView(o);
        orgs.update(null, Wrappers.<Org>lambdaUpdate()
                .eq(Org::getId, id)
                .set(Org::getStatus, "suspended")
                .set(Org::getSuspendedAt, LocalDateTime.now())
                .set(Org::getUpdatedBy, CurrentUser.actor())
                .set(Org::getUpdatedAt, LocalDateTime.now()));
        invalidateTenant(id);
        Org after = require(id);
        audit.record("tenant", id, "suspend", before, baseView(after), AuditService.PLATFORM_ORG_ID);
        return baseView(after);
    }

    @Transactional
    public Map<String, Object> resume(Long id) {
        Org o = require(id);
        Map<String, Object> before = baseView(o);
        LocalDate today = LocalDate.now();
        LocalDate end = endDate(o);
        if (o.getLicenseStartAt() != null && o.getLicenseStartAt().isAfter(today)) {
            throw new BizException(ErrorCode.TENANT_PENDING, "授权尚未生效，无法恢复");
        }
        if (end.isBefore(today)) {
            throw new BizException(ErrorCode.TENANT_EXPIRED, "授权已到期，请先续期再恢复");
        }
        orgs.update(null, Wrappers.<Org>lambdaUpdate()
                .eq(Org::getId, id)
                .set(Org::getStatus, "active")
                .set(Org::getSuspendedAt, null)
                .set(Org::getUpdatedBy, CurrentUser.actor())
                .set(Org::getUpdatedAt, LocalDateTime.now()));
        invalidateTenant(id);
        Org after = require(id);
        audit.record("tenant", id, "resume", before, baseView(after), AuditService.PLATFORM_ORG_ID);
        return baseView(after);
    }

    /* ==================== 内部 ==================== */

    /** 让该租户所有成员的会话缓存立即失效，配合每次请求的状态校验完成“即时下线”。 */
    public void invalidateTenant(Long orgId) {
        members.selectList(Wrappers.<UserTenant>lambdaQuery().eq(UserTenant::getOrgId, orgId))
                .forEach(t -> state.invalidateMember(orgId, t.getUserId()));
    }

    private Org require(Long id) {
        Org o = id == null ? null : orgs.selectById(id);
        if (o == null) throw new BizException(ErrorCode.TENANT_NOT_FOUND, "租户不存在");
        return o;
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = rs.getLong("id");
        java.sql.Date start = rs.getDate("license_start_at");
        java.sql.Timestamp expire = rs.getTimestamp("expire_at");
        String status = effectiveStatus(rs.getString("status"),
                expire == null ? null : expire.toLocalDateTime());
        m.put("orgId", id);
        m.put("name", rs.getString("name"));
        m.put("code", rs.getString("code"));
        m.put("tenantCode", rs.getString("tenant_code"));
        m.put("status", status);
        m.put("plan", rs.getString("plan"));
        m.put("seatLimit", rs.getObject("seat_limit"));
        m.put("members", rs.getInt("members"));
        m.put("devices", rs.getInt("devices"));
        m.put("reports", rs.getInt("reports"));
        m.put("adminName", rs.getString("admin_name"));
        String phone = rs.getString("admin_phone");
        m.put("adminPhone", phone == null ? null : Hashes.maskPhone(phone));
        m.put("licenseStartAt", start == null ? null : start.toLocalDate().toString());
        m.put("licenseEndAt", expire == null ? null : expire.toLocalDateTime().toLocalDate().toString());
        m.put("remainDays", remainDays(expire));
        return m;
    }

    private Map<String, Object> baseView(Org o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", o.getId());
        m.put("name", o.getName());
        m.put("code", o.getCode());
        m.put("tenantCode", o.getTenantCode());
        m.put("status", effectiveStatus(o));
        m.put("plan", o.getPlan());
        m.put("seatLimit", o.getSeatLimit());
        m.put("members", count("select count(*) from user_tenant where org_id = " + o.getId()
                + " and status <> 'removed'"));
        m.put("licenseStartAt", o.getLicenseStartAt() == null ? null : o.getLicenseStartAt().toString());
        m.put("licenseEndAt", endDate(o) == null ? null : endDate(o).toString());
        m.put("remainDays", remainDays(o.getExpireAt()));
        m.put("suspendedAt", o.getSuspendedAt());
        return m;
    }

    /** 展示用有效状态：到期时间已过但定时任务尚未跑到时，也显示为 expired。 */
    public static String effectiveStatus(String status, LocalDateTime expireAt) {
        String st = status == null ? "active" : status;
        if ("disabled".equals(st)) st = "suspended";
        if ("active".equals(st) && expireAt != null && expireAt.isBefore(LocalDateTime.now())) return "expired";
        return st;
    }

    private static String effectiveStatus(Org o) {
        return effectiveStatus(o.getStatus(), o.getExpireAt());
    }

    private static LocalDate endDate(Org o) {
        return o.getExpireAt() == null ? null : o.getExpireAt().toLocalDate();
    }

    private static Long remainDays(java.sql.Timestamp expire) {
        return remainDays(expire == null ? null : expire.toLocalDateTime());
    }

    private static Long remainDays(LocalDateTime expire) {
        if (expire == null) return null;
        return ChronoUnit.DAYS.between(LocalDate.now(), expire.toLocalDate());
    }

    private void assertLicense(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BizException(ErrorCode.TENANT_LICENSE_INVALID, "请填写授权开始与到期日期");
        }
        if (end.isBefore(start)) {
            throw new BizException(ErrorCode.TENANT_LICENSE_INVALID, "到期日期不能早于开始日期");
        }
        if (end.isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.TENANT_LICENSE_INVALID, "到期日期不能早于今天");
        }
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static LocalDate dateOf(Map<String, Object> body, String key, LocalDate fallback) {
        String v = str(body, key);
        if (v == null) return fallback;
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (Exception e) {
            throw new BizException(ErrorCode.TENANT_LICENSE_INVALID, "日期格式应为 YYYY-MM-DD");
        }
    }

    private static int intOf(Map<String, Object> body, String key, int fallback, int min, int max) {
        Object v = body.get(key);
        int n = fallback;
        if (v != null && !String.valueOf(v).isBlank()) {
            try {
                n = Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw BizException.validation("数值格式不正确：" + key);
            }
        }
        if (n < min || n > max) throw BizException.validation("数值超出范围：" + key);
        return n;
    }
}
