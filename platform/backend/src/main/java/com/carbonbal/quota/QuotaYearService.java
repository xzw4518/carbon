package com.carbonbal.quota;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class QuotaYearService {
    private final QuotaYearMapper mapper;
    private final AuditService audit;

    public QuotaYearService(QuotaYearMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<QuotaYear> list(Integer year) {
        return mapper.selectList(Wrappers.<QuotaYear>lambdaQuery()
                .eq(QuotaYear::getOrgId, CurrentUser.orgId())
                .eq(QuotaYear::getDisabled, 0)
                .eq(year != null, QuotaYear::getQuotaYear, year)
                .orderByDesc(QuotaYear::getQuotaYear));
    }

    /** 单条详情：不存在或不属于本企业统一 404。 */
    public QuotaYear detail(Long id) {
        QuotaYear q = id == null ? null : mapper.selectOne(Wrappers.<QuotaYear>lambdaQuery()
                .eq(QuotaYear::getId, id)
                .eq(QuotaYear::getOrgId, CurrentUser.orgId())
                .eq(QuotaYear::getDisabled, 0));
        if (q == null) throw BizException.notFound("年度配额不存在");
        return q;
    }

    @Transactional
    public QuotaYear create(QuotaDtos.QuotaReq req) {
        requireYearFree(req.year(), null);
        QuotaYear q = new QuotaYear();
        q.setOrgId(CurrentUser.orgId());
        q.setQuotaYear(req.year());
        q.setTotalQty(req.total());
        q.setNextYearQty(req.next());
        q.setNote(req.note());
        q.setVersion(0);
        q.setDisabled(0);
        q.setCreatedBy(CurrentUser.username());
        q.setUpdatedBy(CurrentUser.username());
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        mapper.insert(q);
        audit.record("quota_year", q.getId(), "create", null, q);
        return q;
    }

    @Transactional
    public QuotaYear update(Long id, QuotaDtos.QuotaReq req) {
        QuotaYear old = require(id);
        requireYearFree(req.year(), id);
        int rows = mapper.update(null, Wrappers.<QuotaYear>lambdaUpdate()
                .eq(QuotaYear::getId, id)
                .eq(QuotaYear::getOrgId, CurrentUser.orgId())
                .eq(req.version() != null, QuotaYear::getVersion, req.version())
                .set(QuotaYear::getQuotaYear, req.year())
                .set(QuotaYear::getTotalQty, req.total())
                .set(QuotaYear::getNextYearQty, req.next())
                .set(QuotaYear::getNote, req.note())
                .set(QuotaYear::getUpdatedBy, CurrentUser.username())
                .set(QuotaYear::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            QuotaYear latest = mapper.selectById(id);
            throw BizException.conflict("配额年度已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        QuotaYear now = mapper.selectById(id);
        audit.record("quota_year", id, "update", old, now);
        return now;
    }

    @Transactional
    public void remove(Long id) {
        QuotaYear old = require(id);
        mapper.update(null, Wrappers.<QuotaYear>lambdaUpdate()
                .eq(QuotaYear::getId, id)
                .eq(QuotaYear::getOrgId, CurrentUser.orgId())
                .set(QuotaYear::getDisabled, 1)
                .set(QuotaYear::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("quota_year", id, "delete", old, null);
    }

    private void requireYearFree(Integer year, Long selfId) {
        Long n = mapper.selectCount(Wrappers.<QuotaYear>lambdaQuery()
                .eq(QuotaYear::getOrgId, CurrentUser.orgId())
                .eq(QuotaYear::getQuotaYear, year)
                .eq(QuotaYear::getDisabled, 0)
                .ne(selfId != null, QuotaYear::getId, selfId));
        if (n != null && n > 0) throw BizException.duplicate("该年度配额已存在", Map.of("year", year));
    }

    private QuotaYear require(Long id) {
        QuotaYear q = mapper.selectById(id);
        if (q == null || Integer.valueOf(1).equals(q.getDisabled())) throw BizException.notFound("配额年度不存在");
        if (!q.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的配额");
        return q;
    }
}
