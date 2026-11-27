package com.carbonbal.alert;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {
    private final AlertRuleMapper rules;
    private final AlertEventMapper events;
    private final AlertRecordMapper records;
    private final AuditService audit;

    public AlertService(AlertRuleMapper rules, AlertEventMapper events,
                        AlertRecordMapper records, AuditService audit) {
        this.rules = rules;
        this.events = events;
        this.records = records;
        this.audit = audit;
    }

    public PageResult<AlertRule> listRules(int page, int size, String keyword, String level, Integer enabled) {
        Page<AlertRule> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        rules.selectPage(p, Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getOrgId, CurrentUser.orgId())
                .eq(AlertRule::getDisabled, 0)
                .eq(level != null && !level.isBlank(), AlertRule::getLevel, level)
                .eq(enabled != null, AlertRule::getEnabled, enabled)
                .like(keyword != null && !keyword.isBlank(), AlertRule::getName, keyword)
                .orderByDesc(AlertRule::getId));
        return PageResult.of(p);
    }

    /** 单条规则详情：不存在或不属于本企业统一 404。 */
    public AlertRule detailRule(Long id) {
        AlertRule r = id == null ? null : rules.selectOne(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getId, id)
                .eq(AlertRule::getOrgId, CurrentUser.orgId())
                .eq(AlertRule::getDisabled, 0));
        if (r == null) throw BizException.notFound("预警规则不存在");
        return r;
    }

    @Transactional
    public AlertRule createRule(AlertDtos.RuleReq req) {
        AlertRule r = new AlertRule();
        r.setOrgId(CurrentUser.orgId());
        applyRule(r, req);
        r.setDisabled(0);
        r.setVersion(0);
        r.setCreatedBy(CurrentUser.username());
        r.setUpdatedBy(CurrentUser.username());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        rules.insert(r);
        audit.record("alert_rule", r.getId(), "create", null, r);
        return r;
    }

    @Transactional
    public AlertRule updateRule(Long id, AlertDtos.RuleReq req) {
        AlertRule old = requireRule(id);
        int rows = rules.update(null, Wrappers.<AlertRule>lambdaUpdate()
                .eq(AlertRule::getId, id)
                .eq(req.version() != null, AlertRule::getVersion, req.version())
                .set(AlertRule::getName, req.name())
                .set(AlertRule::getMetric, req.metric())
                .set(AlertRule::getOp, req.op())
                .set(AlertRule::getThreshold, req.threshold())
                .set(AlertRule::getUnit, req.unit())
                .set(AlertRule::getCool, req.cool() == null ? 60 : req.cool())
                .set(AlertRule::getLevel, req.level())
                .set(AlertRule::getEnabled, req.enabled() == null ? 1 : req.enabled())
                .set(AlertRule::getRemark, req.remark())
                .set(AlertRule::getUpdatedBy, CurrentUser.username())
                .set(AlertRule::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AlertRule latest = rules.selectById(id);
            throw BizException.conflict("规则已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        AlertRule now = rules.selectById(id);
        audit.record("alert_rule", id, "update", old, now);
        return now;
    }

    @Transactional
    public void removeRule(Long id) {
        AlertRule old = requireRule(id);
        rules.update(null, Wrappers.<AlertRule>lambdaUpdate()
                .eq(AlertRule::getId, id).set(AlertRule::getDisabled, 1)
                .setSql("version = version + 1"));
        audit.record("alert_rule", id, "delete", old, null);
    }

    public PageResult<AlertEvent> pageEvents(int page, int size, String keyword, String type) {
        Page<AlertEvent> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        events.selectPage(p, Wrappers.<AlertEvent>lambdaQuery()
                .eq(AlertEvent::getOrgId, CurrentUser.orgId())
                .eq(AlertEvent::getDisabled, 0)
                .eq(type != null && !type.isBlank(), AlertEvent::getType, type)
                .like(keyword != null && !keyword.isBlank(), AlertEvent::getName, keyword)
                .orderByDesc(AlertEvent::getId));
        return PageResult.of(p);
    }

    public List<AlertEvent> listEvents(String type) {
        return events.selectList(Wrappers.<AlertEvent>lambdaQuery()
                .eq(AlertEvent::getOrgId, CurrentUser.orgId())
                .eq(AlertEvent::getDisabled, 0)
                .eq(type != null && !type.isBlank(), AlertEvent::getType, type)
                .orderByDesc(AlertEvent::getId));
    }

    @Transactional
    public AlertEvent createEvent(AlertDtos.EventReq req) {
        AlertEvent e = new AlertEvent();
        e.setOrgId(CurrentUser.orgId());
        e.setName(req.name());

        e.setRemark(req.remark());
        e.setType(req.type());
        e.setMetric(req.metric());
        e.setOp(req.op());
        e.setThreshold(req.threshold());
        e.setLevel(req.level());
        e.setBuiltin(0);
        e.setEnabled(req.enabled() == null ? 1 : req.enabled());
        e.setDisabled(0);
        e.setVersion(0);
        e.setCreatedBy(CurrentUser.username());
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        events.insert(e);
        audit.record("alert_event", e.getId(), "create", null, e);
        return e;
    }

    @Transactional
    public AlertEvent updateEvent(Long id, AlertDtos.EventReq req) {
        AlertEvent old = events.selectById(id);
        if (old == null || Integer.valueOf(1).equals(old.getDisabled())) throw BizException.notFound("事件不存在");
        if (!old.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权操作其他企业的数据");
        events.update(null, Wrappers.<AlertEvent>lambdaUpdate()
                .eq(AlertEvent::getId, id)
                .set(AlertEvent::getName, req.name())
                .set(AlertEvent::getType, req.type())
                .set(AlertEvent::getMetric, req.metric())
                .set(AlertEvent::getOp, req.op())
                .set(AlertEvent::getThreshold, req.threshold())
                .set(AlertEvent::getLevel, req.level())
                .set(AlertEvent::getRemark, req.remark())
                .set(AlertEvent::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        AlertEvent now = events.selectById(id);
        audit.record("alert_event", id, "update", old, now);
        return now;
    }

    @Transactional
    public AlertEvent toggleEvent(Long id, Integer enabled) {
        AlertEvent old = events.selectById(id);
        if (old == null || Integer.valueOf(1).equals(old.getDisabled())) throw BizException.notFound("事件不存在");
        if (!old.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权操作其他企业的数据");
        events.update(null, Wrappers.<AlertEvent>lambdaUpdate()
                .eq(AlertEvent::getId, id).set(AlertEvent::getEnabled, enabled)
                .set(AlertEvent::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        AlertEvent now = events.selectById(id);
        audit.record("alert_event", id, "toggle", old, now);
        return now;
    }

    private AlertRule requireRule(Long id) {
        AlertRule r = rules.selectById(id);
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("规则不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权操作其他企业的数据");
        return r;
    }

    private void applyRule(AlertRule r, AlertDtos.RuleReq req) {
        r.setName(req.name());

        r.setRemark(req.remark());
        r.setMetric(req.metric());
        r.setOp(req.op());
        r.setThreshold(req.threshold());
        r.setUnit(req.unit());
        r.setCool(req.cool() == null ? 60 : req.cool());
        r.setLevel(req.level());
        r.setEnabled(req.enabled() == null ? 1 : req.enabled());
        r.setRemark(req.remark());
    }
}
