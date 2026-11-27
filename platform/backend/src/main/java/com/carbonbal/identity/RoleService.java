package com.carbonbal.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.auth.AppUser;
import com.carbonbal.auth.AppUserMapper;
import com.carbonbal.auth.AuthStateService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Hashes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理：角色只是一组页面编码，不支持动作权限与数据范围。
 * <p>
 * 约束：每租户 ≤30 个；名称租户内唯一；默认角色【运营】不可删除；
 * 有成员在用的角色不可删除（先转移）；最后一个角色不可删除。
 */
@Service
public class RoleService {

    public static final int MAX_ROLES = 30;
    public static final String DEFAULT_NAME = "运营";

    private final TenantRoleMapper roles;
    private final TenantRolePageMapper rolePages;
    private final UserTenantMapper members;
    private final AppUserMapper users;
    private final AuthStateService state;
    private final JdbcTemplate jdbc;

    public RoleService(TenantRoleMapper roles, TenantRolePageMapper rolePages, UserTenantMapper members,
                       AppUserMapper users, AuthStateService state, JdbcTemplate jdbc) {
        this.roles = roles;
        this.rolePages = rolePages;
        this.members = members;
        this.users = users;
        this.state = state;
        this.jdbc = jdbc;
    }

    /* ==================== 查询 ==================== */

    public List<Map<String, Object>> list() {
        Long orgId = CurrentUser.orgId();
        ensureDefault(orgId);
        List<TenantRole> list = roles.selectList(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, orgId)
                .eq(TenantRole::getDisabled, 0)
                .orderByDesc(TenantRole::getIsDefault)
                .orderByAsc(TenantRole::getId));
        Map<Long, Integer> userCount = new LinkedHashMap<>();
        jdbc.query("select role_id, count(*) n from user_tenant where org_id = ? and status <> 'removed' "
                        + "and role_id is not null group by role_id",
                rs -> { userCount.put(rs.getLong("role_id"), rs.getInt("n")); }, orgId);
        Map<Long, Integer> pageCount = new LinkedHashMap<>();
        jdbc.query("select role_id, count(*) n from tenant_role_page where org_id = ? group by role_id",
                rs -> { pageCount.put(rs.getLong("role_id"), rs.getInt("n")); }, orgId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantRole r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleId", r.getId());
            m.put("name", r.getName());
            m.put("remark", r.getRemark());
            m.put("defaultRole", Integer.valueOf(1).equals(r.getIsDefault()));
            m.put("users", userCount.getOrDefault(r.getId(), 0));
            m.put("pages", pageCount.getOrDefault(r.getId(), 0));
            m.put("pageTotal", PageCatalog.CODES.size());
            m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString().replace('T', ' '));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> detail(Long id) {
        TenantRole r = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roleId", r.getId());
        m.put("name", r.getName());
        m.put("remark", r.getRemark());
        m.put("defaultRole", Integer.valueOf(1).equals(r.getIsDefault()));
        m.put("pages", pagesOf(id));
        m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString().replace('T', ' '));
        return m;
    }

    public List<Map<String, Object>> usersOf(Long id) {
        require(id);
        return jdbc.query("""
                select t.id, t.user_type, u.display_name, u.phone
                from user_tenant t join app_user u on u.id = t.user_id
                where t.org_id = ? and t.role_id = ? and t.status <> 'removed'
                order by t.id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("membershipId", rs.getLong("id"));
            m.put("displayName", rs.getString("display_name"));
            m.put("phone", Hashes.maskPhone(rs.getString("phone")));
            return m;
        }, CurrentUser.orgId(), id);
    }

    /* ==================== 写入 ==================== */

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Long orgId = CurrentUser.orgId();
        String name = str(body, "name");
        if (name == null) throw BizException.validation("请填写角色名称");
        if (name.length() > 32) throw BizException.validation("角色名称不超过 32 个字符");
        long count = roles.selectCount(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getDisabled, 0));
        if (count >= MAX_ROLES) {
            throw new BizException(ErrorCode.ROLE_LIMIT, "每个租户最多 " + MAX_ROLES + " 个角色", Map.of("limit", MAX_ROLES));
        }
        if (roles.selectCount(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getName, name)) > 0) {
            throw BizException.duplicate("角色名称已存在", name);
        }
        List<String> codes = pagesFrom(body);
        TenantRole r = new TenantRole();
        r.setOrgId(orgId);
        r.setName(name);
        r.setRemark(str(body, "remark"));
        r.setIsDefault(0);
        r.setVersion(0);
        r.setDisabled(0);
        r.setCreatedBy(CurrentUser.actor());
        r.setUpdatedBy(CurrentUser.actor());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        roles.insert(r);
        bindPages(orgId, r.getId(), codes);
        return detail(r.getId());
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        TenantRole r = require(id);
        Long orgId = CurrentUser.orgId();
        String name = str(body, "name");
        if (name != null && !name.equals(r.getName())) {
            if (roles.selectCount(Wrappers.<TenantRole>lambdaQuery()
                    .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getName, name)
                    .ne(TenantRole::getId, id)) > 0) {
                throw BizException.duplicate("角色名称已存在", name);
            }
        }
        List<String> codes = body.get("pages") == null ? null : pagesFrom(body);
        roles.update(null, Wrappers.<TenantRole>lambdaUpdate()
                .eq(TenantRole::getId, id)
                .set(name != null, TenantRole::getName, name)
                .set(body.get("remark") != null, TenantRole::getRemark, str(body, "remark"))
                .set(TenantRole::getUpdatedBy, CurrentUser.actor())
                .set(TenantRole::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (codes != null) {
            rolePages.delete(Wrappers.<TenantRolePage>lambdaQuery().eq(TenantRolePage::getRoleId, id));
            bindPages(orgId, id, codes);
        }
        invalidateMembersOf(id);
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        TenantRole r = require(id);
        if (Integer.valueOf(1).equals(r.getIsDefault())) {
            throw new BizException(ErrorCode.ROLE_DEFAULT, "默认角色【" + r.getName() + "】不可删除");
        }
        Long used = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, CurrentUser.orgId())
                .eq(UserTenant::getRoleId, id)
                .ne(UserTenant::getStatus, "removed"));
        if (used != null && used > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE,
                    "该角色下还有 " + used + " 名用户，请先转移到其他角色", Map.of("users", used));
        }
        long count = roles.selectCount(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, CurrentUser.orgId()).eq(TenantRole::getDisabled, 0));
        if (count <= 1) throw new BizException(ErrorCode.ROLE_LIMIT, "至少保留一个角色");
        rolePages.delete(Wrappers.<TenantRolePage>lambdaQuery().eq(TenantRolePage::getRoleId, id));
        roles.deleteById(id);
    }

    /* ==================== 供认证使用 ==================== */

    /** 租户管理员与平台账号可进入全部页面。 */
    public static List<String> allPages() {
        return PageCatalog.CODES;
    }

    public List<String> pagesOf(Long roleId) {
        if (roleId == null) return List.of();
        return rolePages.selectList(Wrappers.<TenantRolePage>lambdaQuery()
                        .eq(TenantRolePage::getRoleId, roleId)
                        .orderByAsc(TenantRolePage::getId))
                .stream().map(TenantRolePage::getPageCode).toList();
    }

    public List<String> memberPages(Long orgId, Long userId) {
        UserTenant t = members.selectOne(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, orgId).eq(UserTenant::getUserId, userId).last("LIMIT 1"));
        if (t == null) return List.of();
        if ("tenant_admin".equals(t.getUserType()) || "admin".equals(t.getRole())) return allPages();
        return pagesOf(t.getRoleId());
    }

    public String nameOf(Long roleId) {
        TenantRole r = roleId == null ? null : roles.selectById(roleId);
        return r == null ? "" : r.getName();
    }

    /**
     * 按名称查当前租户<b>启用中</b>的角色 ID，供成员导入等「按角色名填表」的场景使用。
     *
     * @param name 角色名称（前后空格会被 trim；null/空直接返回 null）
     * @return 角色 ID；不存在或已停用时返回 {@code null}（由调用方决定如何报错）
     */
    public Long idByName(String name) {
        if (name == null || name.isBlank()) return null;
        TenantRole r = roles.selectOne(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, CurrentUser.orgId())
                .eq(TenantRole::getName, name.trim())
                .eq(TenantRole::getDisabled, 0)
                .last("LIMIT 1"));
        return r == null ? null : r.getId();
    }

    public Long defaultRoleId(Long orgId) {
        ensureDefault(orgId);
        TenantRole r = roles.selectOne(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getIsDefault, 1).last("LIMIT 1"));
        return r == null ? null : r.getId();
    }

    /**
     * 幂等：保证每个租户都有默认角色，且默认角色拥有<b>除特权页面外</b>的全部页面。
     * <p>
     * 两处修正：
     * <ol>
     *   <li>只回补非特权页面（P1-1 根因）：历史实现每次列表都把全部 32 个页面补齐到默认角色，
     *       导致普通成员自动获得 {@code group.overview}（集团汇总），横向越权看到兄弟企业；</li>
     *   <li>捕获并发插入的唯一键冲突（P2-4）：{@code uk_tenant_role_org_name} 已能兜住重复的【运营】，
     *       但第二个事务会抛 {@code DuplicateKeyException} 变成 500，这里重查后继续。</li>
     * </ol>
     *
     * @param orgId 租户 ID，可为 null（null 直接返回）
     */
    @Transactional
    public void ensureDefault(Long orgId) {
        if (orgId == null) return;
        TenantRole r = findDefault(orgId);
        if (r == null) {
            r = newDefaultRole(orgId);
            try {
                roles.insert(r);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发建默认角色：另一个事务已插入同名/同默认角色，重查即可
                r = findDefault(orgId);
                if (r == null) {
                    r = roles.selectOne(Wrappers.<TenantRole>lambdaQuery()
                            .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getName, DEFAULT_NAME).last("LIMIT 1"));
                }
                if (r == null) throw e;
            }
        }
        List<String> bound = pagesOf(r.getId());
        List<String> missing = PageCatalog.DEFAULT_ROLE_CODES.stream().filter(c -> !bound.contains(c)).toList();
        if (!missing.isEmpty()) bindPagesIgnoringDuplicates(orgId, r.getId(), missing);
    }

    private TenantRole findDefault(Long orgId) {
        return roles.selectOne(Wrappers.<TenantRole>lambdaQuery()
                .eq(TenantRole::getOrgId, orgId).eq(TenantRole::getIsDefault, 1).last("LIMIT 1"));
    }

    private static TenantRole newDefaultRole(Long orgId) {
        TenantRole r = new TenantRole();
        r.setOrgId(orgId);
        r.setName(DEFAULT_NAME);
        r.setRemark("默认角色：可进入全部业务页面（集团汇总需管理员显式授予）");
        r.setIsDefault(1);
        r.setVersion(0);
        r.setDisabled(0);
        r.setCreatedBy("system");
        r.setUpdatedBy("system");
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    /** 角色或页面变化后，让该角色下成员的令牌立即失效（页面集合写在令牌里）。 */
    public void invalidateMembersOf(Long roleId) {
        List<UserTenant> list = members.selectList(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getRoleId, roleId));
        for (UserTenant t : list) {
            users.update(null, Wrappers.<AppUser>lambdaUpdate()
                    .eq(AppUser::getId, t.getUserId())
                    .setSql("token_version = token_version + 1"));
            state.invalidateMember(t.getOrgId(), t.getUserId());
        }
    }

    /* ==================== 内部 ==================== */

    private void bindPages(Long orgId, Long roleId, List<String> codes) {
        for (String code : codes) {
            TenantRolePage p = new TenantRolePage();
            p.setOrgId(orgId);
            p.setRoleId(roleId);
            p.setPageCode(code);
            rolePages.insert(p);
        }
    }

    /**
     * 回补页面时忽略并发重复（{@code uk_tenant_role_page} 兜底），其余异常继续上抛。
     * 仅用于 {@link #ensureDefault} 的"幂等补齐"语义，不做静默吞错。
     */
    private void bindPagesIgnoringDuplicates(Long orgId, Long roleId, List<String> codes) {
        for (String code : codes) {
            TenantRolePage p = new TenantRolePage();
            p.setOrgId(orgId);
            p.setRoleId(roleId);
            p.setPageCode(code);
            try {
                rolePages.insert(p);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 另一个事务已绑定同一页面：幂等目标已达成
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> pagesFrom(Map<String, Object> body) {
        Object v = body.get("pages");
        List<String> codes = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String code = String.valueOf(o).trim();
                if (code.isEmpty()) continue;
                if (!PageCatalog.exists(code)) {
                    throw BizException.validation("页面编码不存在：" + code);
                }
                if (!codes.contains(code)) codes.add(code);
            }
        }
        if (codes.isEmpty()) throw BizException.validation("至少勾选一个页面");
        if (!codes.contains("home")) codes.add(0, "home"); // 首页是所有角色的落地页
        return codes;
    }

    private TenantRole require(Long id) {
        TenantRole r = id == null ? null : roles.selectById(id);
        if (r == null || !CurrentUser.orgId().equals(r.getOrgId())) {
            throw BizException.notFound("角色不存在");
        }
        return r;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }
}
