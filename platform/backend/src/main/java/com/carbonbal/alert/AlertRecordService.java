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
public class AlertRecordService {
    private final AlertRecordMapper mapper;
    private final AuditService audit;

    public AlertRecordService(AlertRecordMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<AlertRecord> list(int page, int size, String level, String status,
                                        Integer isRead, String keyword, String metric, String device,
                                        LocalDateTime from, LocalDateTime to) {
        Page<AlertRecord> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                .eq(AlertRecord::getDisabled, 0)
                .eq(level != null && !level.isBlank(), AlertRecord::getLevel, level)
                .eq(status != null && !status.isBlank(), AlertRecord::getStatus, status)
                .eq(isRead != null, AlertRecord::getIsRead, isRead)
                .eq(metric != null && !metric.isBlank(), AlertRecord::getMetric, metric)
                .like(device != null && !device.isBlank(), AlertRecord::getDeviceCode, device)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(AlertRecord::getTitle, keyword).or().like(AlertRecord::getDeviceCode, keyword))
                .ge(from != null, AlertRecord::getOccurredAt, from)
                .le(to != null, AlertRecord::getOccurredAt, to)
                .orderByDesc(AlertRecord::getOccurredAt)
                .orderByDesc(AlertRecord::getId));
        return PageResult.of(p);
    }

    public Map<String, Long> stats() {
        long unread = count(null, 0);
        long open = count("open", null);
        long high = count("open", null, "high");
        long mid = count("open", null, "mid");
        return Map.of("unread", unread, "open", open, "highOpen", high, "midOpen", mid);
    }

    private long count(String status, Integer isRead, String... level) {
        var q = Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                .eq(AlertRecord::getDisabled, 0)
                .eq(status != null, AlertRecord::getStatus, status)
                .eq(isRead != null, AlertRecord::getIsRead, isRead);
        if (level.length > 0) q.eq(AlertRecord::getLevel, level[0]);
        Long n = mapper.selectCount(q);
        return n == null ? 0 : n;
    }

    @Transactional
    public AlertRecord markRead(Long id) {
        AlertRecord old = require(id);
        mapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getId, id).set(AlertRecord::getIsRead, 1));
        AlertRecord now = mapper.selectById(id);
        audit.record("alert_record", id, "read", Map.of("isRead", old.getIsRead()), Map.of("isRead", 1));
        return now;
    }

    @Transactional
    public int markAllRead() {
        int n = mapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                .eq(AlertRecord::getDisabled, 0)
                .eq(AlertRecord::getIsRead, 0)
                .set(AlertRecord::getIsRead, 1));
        audit.record("alert_record", "bulk", "read-all", null, Map.of("affected", n));
        return n;
    }

    @Transactional
    public AlertRecord handle(Long id, String remark) {
        AlertRecord old = require(id);
        if (!"open".equals(old.getStatus())) throw BizException.validation("仅待处理记录可以标记处理");
        mapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getId, id)
                .set(AlertRecord::getStatus, "handled")
                .set(AlertRecord::getHandledBy, CurrentUser.username())
                .set(AlertRecord::getHandledAt, LocalDateTime.now())
                .set(AlertRecord::getIsRead, 1)
                .set(remark != null, AlertRecord::getRemark, remark));
        AlertRecord now = mapper.selectById(id);
        audit.record("alert_record", id, "handle", old, now);
        return now;
    }

    @Transactional
    public int batchRemove(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw BizException.validation("请选择要删除的记录");
        int n = mapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                .in(AlertRecord::getId, ids)
                .set(AlertRecord::getDisabled, 1));
        audit.record("alert_record", "bulk", "delete", null, Map.of("ids", ids, "affected", n));
        return n;
    }

    private AlertRecord require(Long id) {
        AlertRecord r = mapper.selectById(id);
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("预警记录不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权操作其他企业的数据");
        return r;
    }
}
