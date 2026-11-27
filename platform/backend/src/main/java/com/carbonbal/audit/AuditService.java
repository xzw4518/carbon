package com.carbonbal.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 审计只追加：不提供更新、删除接口。序列化失败时降级为字符串，绝不阻断主流程。 */
@Service
public class AuditService {

    /** 平台级操作的审计归属：不属于任何租户，用 0 与租户数据隔离。 */
    public static final long PLATFORM_ORG_ID = 0L;

    private final AuditLogMapper mapper;
    private final ObjectMapper json;

    public AuditService(AuditLogMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void record(String entity, Object entityId, String action, Object before, Object after) {
        record(entity, entityId, action, before, after, null);
    }

    /**
     * @param orgId 归属租户；传空时取当前上下文。平台操作显式传 {@link #PLATFORM_ORG_ID}。
     */
    public void record(String entity, Object entityId, String action, Object before, Object after, Long orgId) {
        AuditLog l = new AuditLog();
        l.setOrgId(orgId == null ? CurrentUser.orgId() : orgId);
        l.setActorId(CurrentUser.userIdOrNull());
        l.setEntity(entity);
        l.setEntityId(String.valueOf(entityId));
        l.setAction(action);
        l.setBeforeJson(toJson(before));
        l.setAfterJson(toJson(after));
        l.setActor(CurrentUser.actor());
        l.setActionLabel(actionLabel(action));
        l.setLoggedAt(LocalDateTime.now());
        mapper.insert(l);
    }

    public List<AuditLog> list(String entity, String entityId, int limit) {
        return mapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getOrgId, CurrentUser.orgId())
                .eq(entity != null, AuditLog::getEntity, entity)
                .eq(entityId != null, AuditLog::getEntityId, entityId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    /** 租户内审计分页：时间区间、模块、动作、操作人、关键词（对象 ID / 操作人 / 动作名）。 */
    public Map<String, Object> page(int page, int size, String entity, String action, String actor,
                                    String keyword, String from, String to) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        Long total = mapper.selectCount(query(entity, action, actor, keyword, from, to));
        List<AuditLog> rows = mapper.selectList(query(entity, action, actor, keyword, from, to)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + s + " OFFSET " + (long) (p - 1) * s));
        // 历史数据可能没有中文标签或标签与动作同名：读时按同一份映射补齐
        for (AuditLog l : rows) {
            if (l.getActionLabel() == null || l.getActionLabel().equals(l.getAction())) {
                l.setActionLabel(actionLabel(l.getAction()));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total == null ? 0 : total);
        m.put("rows", rows);
        m.put("page", p);
        m.put("size", s);
        return m;
    }

    /** 筛选下拉的候选值：当前租户实际出现过的模块与动作，按次数倒序。 */
    public Map<String, Object> facets() {
        List<Map<String, Object>> entityRows = mapper.selectMaps(new QueryWrapper<AuditLog>()
                .select("entity as code", "count(*) as cnt")
                .eq("org_id", CurrentUser.orgId())
                .groupBy("entity")
                .orderByDesc("cnt"));
        List<Map<String, Object>> actionRows = mapper.selectMaps(new QueryWrapper<AuditLog>()
                .select("action as code", "max(action_label) as label", "count(*) as cnt")
                .eq("org_id", CurrentUser.orgId())
                .groupBy("action")
                .orderByDesc("cnt"));
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> r : entityRows) {
            String code = String.valueOf(r.get("code"));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", code);
            o.put("label", entityLabel(code));
            o.put("count", r.get("cnt"));
            entities.add(o);
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> r : actionRows) {
            String code = String.valueOf(r.get("code"));
            Object label = r.get("label");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", code);
            o.put("label", label == null ? actionLabel(code) : label);
            o.put("count", r.get("cnt"));
            actions.add(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entities", entities);
        m.put("actions", actions);
        return m;
    }

    private LambdaQueryWrapper<AuditLog> query(String entity, String action, String actor,
                                               String keyword, String from, String to) {
        LambdaQueryWrapper<AuditLog> q = Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getOrgId, CurrentUser.orgId())
                .eq(notBlank(entity), AuditLog::getEntity, trim(entity))
                .eq(notBlank(action), AuditLog::getAction, trim(action));
        if (notBlank(actor)) q.like(AuditLog::getActor, trim(actor));
        if (notBlank(keyword)) {
            String k = "%" + keyword.trim() + "%";
            q.and(w -> w.like(AuditLog::getEntityId, k)
                    .or().like(AuditLog::getActor, k)
                    .or().like(AuditLog::getActionLabel, k));
        }
        LocalDateTime begin = startOf(from);
        LocalDateTime end = startOf(to);
        if (begin != null) q.ge(AuditLog::getLoggedAt, begin);
        if (end != null) q.lt(AuditLog::getLoggedAt, end.plusDays(1));
        return q;
    }

    /** 日期筛选：空值不过滤；格式错误明确报错，不能静默忽略（否则会返回全量，误导排查）。 */
    private static LocalDateTime startOf(String date) {
        if (!notBlank(date)) return null;
        String s = date.trim();
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay();
        } catch (Exception e) {
            throw BizException.validation("日期格式应为 YYYY-MM-DD");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 审计模块的中文名，与页面口径保持一致。 */
    public static String entityLabel(String entity) {
        if (entity == null) return null;
        return switch (entity) {
            case "tenant" -> "租户";
            case "user_tenant" -> "成员";
            case "account" -> "账号";
            case "role" -> "角色";
            case "product" -> "产品";
            case "production_value" -> "产品产值";
            case "total_value" -> "生产总值";
            case "quota_year" -> "年度配额";
            case "accounting_period" -> "核算期间";
            case "report" -> "报告";
            case "energy" -> "能源";
            case "footprint" -> "碳足迹";
            case "supplier" -> "供应链";
            case "asset" -> "碳资产";
            case "trade" -> "交易";
            case "evidence" -> "原始凭证";
            case "facility" -> "设施";
            case "device" -> "设备";
            case "alert_rule" -> "预警设置";
            case "alert_record" -> "预警记录";
            case "import" -> "批量导入";
            case "file_object" -> "文件";
            case "org" -> "企业信息";
            case "external_grant" -> "外部授权";
            default -> entity;
        };
    }

    /** 审计动作的中文名，便于业务人员阅读。 */
    public static String actionLabel(String action) {
        if (action == null) return null;
        return switch (action) {
            case "create" -> "新增";
            case "update" -> "修改";
            case "delete" -> "删除";
            case "remove" -> "移出";
            case "calculate" -> "核算";
            case "submit" -> "提交";
            case "review" -> "复核";
            case "approve" -> "批准";
            case "reject" -> "退回";
            case "lock" -> "锁定";
            case "unlock" -> "解锁";
            case "login" -> "登录";
            case "logout" -> "退出";
            case "reset-password" -> "重置密码";
            case "change-password" -> "修改密码";
            case "renew" -> "续期";
            case "suspend" -> "停用";
            case "resume" -> "恢复";
            case "enter" -> "进入租户";
            case "exit" -> "退出租户";
            case "enable" -> "启用";
            case "disable" -> "停用";
            case "upload" -> "上传";
            case "download" -> "下载";
            case "import" -> "导入";
            case "export" -> "导出";
            case "generate" -> "生成";
            case "assign" -> "交接";
            case "grant" -> "授权";
            case "revoke" -> "取消授权";
            case "archive" -> "归档";
            default -> action;
        };
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "\"<unserializable:" + o.getClass().getSimpleName() + ">\"";
        }
    }
}
