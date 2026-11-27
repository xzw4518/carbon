package com.carbonbal.group;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.identity.UserTenant;
import com.carbonbal.identity.UserTenantMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集团—工厂汇总：把当前账号<b>有权查看</b>的企业按集团聚合。
 * <p>
 * 数据范围（P1-1 双层校验的第二层）：页面门禁只决定"能不能进这个页面"，
 * 决定"能看几家企业"的是成员关系：
 * <ul>
 *   <li>普通成员：只看自己 status=active 的成员关系企业，<b>绝不</b>展开兄弟企业；</li>
 *   <li>集团管理员：对某个父企业持有 admin 级成员关系（{@code role=admin} 或 {@code user_type=tenant_admin}）时，
 *       才把该父企业下的子企业一并纳入；</li>
 *   <li>平台超管代管（{@code imp}）：返回全部企业。</li>
 * </ul>
 * 跨企业读用量，直接用 JdbcTemplate 显式带 orgIds，避免逐租户循环。
 */
@Service
public class GroupService {

    private final UserTenantMapper members;
    private final JdbcTemplate jdbc;

    public GroupService(UserTenantMapper members, JdbcTemplate jdbc) {
        this.members = members;
        this.jdbc = jdbc;
    }

    public Map<String, Object> summary(Integer year) {
        Long uid = CurrentUser.userIdOrNull();
        int y = year == null ? java.time.LocalDate.now().getYear() : year;

        List<Long> memberOrgIds = new ArrayList<>();
        Set<Long> adminOrgIds = new LinkedHashSet<>();
        if (uid != null) {
            for (UserTenant t : members.selectList(Wrappers.<UserTenant>lambdaQuery()
                    .eq(UserTenant::getUserId, uid)
                    .eq(UserTenant::getStatus, "active"))) {
                if (t.getOrgId() == null) continue;
                memberOrgIds.add(t.getOrgId());
                if (isTenantAdmin(t)) adminOrgIds.add(t.getOrgId());
            }
        }
        if (memberOrgIds.isEmpty()) memberOrgIds.add(CurrentUser.orgId());

        Set<Long> visible = new LinkedHashSet<>(memberOrgIds);
        if (CurrentUser.impersonating()) {
            // 平台超管代管：可见全部企业（代管令牌由平台账号签发，过滤器已校验 platformAdmin）
            jdbc.queryForList("select id from org order by id", Long.class).forEach(visible::add);
        } else {
            // 需要展开子企业的父企业：本人是其 admin 级成员的父企业
            //  ① 本人所属企业本身就是父企业（集团总部管理员）
            //  ② 本人所属企业挂在某个父企业下，且本人对该父企业是管理员（兄弟工厂一并可见）
            Set<Long> expandParents = new LinkedHashSet<>();
            for (Long id : memberOrgIds) {
                if (adminOrgIds.contains(id)) expandParents.add(id);
            }
            for (Long parentId : parentIdsOf(memberOrgIds)) {
                if (adminOrgIds.contains(parentId)) expandParents.add(parentId);
            }
            for (Long parentId : expandParents) {
                visible.add(parentId);
                jdbc.queryForList("select id from org where parent_org_id = ?", Long.class, parentId)
                        .forEach(visible::add);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalEmission = BigDecimal.ZERO;
        BigDecimal totalEnergy = BigDecimal.ZERO;
        for (Long orgId : visible) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orgId", orgId);
            Map<String, Object> org = jdbc.query(
                    "select name, org_level, parent_org_id, status from org where id = ?",
                    rs -> rs.next() ? Map.of("name", rs.getString("name"),
                            "level", rs.getInt("org_level"),
                            "parentId", rs.getObject("parent_org_id") == null ? 0L : rs.getLong("parent_org_id"),
                            "status", rs.getString("status")) : Map.of(), orgId);
            m.put("name", org.getOrDefault("name", "企业 " + orgId));
            m.put("level", org.getOrDefault("level", 1));
            m.put("parentId", org.getOrDefault("parentId", 0L));
            m.put("status", org.getOrDefault("status", "active"));

            BigDecimal emission = jdbc.query(
                    "select coalesce(sum(total), 0) from accounting_period where org_id = ? and period like ?",
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, orgId, y + "-%");
            BigDecimal energy = jdbc.query(
                    "select coalesce(sum(qty * rate), 0) from device_reading where org_id = ? and year(ts) = ?",
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, orgId, y);
            Integer devices = jdbc.queryForObject(
                    "select count(*) from device where org_id = ? and disabled = 0", Integer.class, orgId);
            Integer people = jdbc.queryForObject(
                    "select count(*) from user_tenant where org_id = ? and status = 'active'", Integer.class, orgId);
            m.put("emission", emission);
            m.put("energy", energy);
            m.put("devices", devices);
            m.put("members", people);
            totalEmission = totalEmission.add(emission == null ? BigDecimal.ZERO : emission);
            totalEnergy = totalEnergy.add(energy == null ? BigDecimal.ZERO : energy);
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", y);
        out.put("orgs", rows);
        out.put("orgCount", rows.size());
        out.put("totalEmission", totalEmission);
        out.put("totalEnergy", totalEnergy);
        out.put("multiOrg", rows.size() > 1);
        return out;
    }

    /** admin 级成员关系：与 {@code AuthStateService.member()} 的归一化口径保持一致。 */
    private static boolean isTenantAdmin(UserTenant t) {
        return "admin".equals(t.getRole()) || "tenant_admin".equals(t.getUserType());
    }

    /** 这些企业挂靠的父企业（去重，保持稳定顺序）。 */
    private List<Long> parentIdsOf(List<Long> orgIds) {
        if (orgIds.isEmpty()) return List.of();
        List<Long> parents = jdbc.queryForList(
                "select distinct parent_org_id from org where id in (" + placeholders(orgIds)
                        + ") and parent_org_id is not null",
                Long.class, orgIds.toArray());
        return parents.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String placeholders(List<Long> ids) {
        return String.join(",", ids.stream().map(x -> "?").toList());
    }
}
