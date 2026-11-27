package com.carbonbal.factor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class FactorService {
    private static final List<String> SCOPES = List.of("national", "local", "customer");

    private final EmissionFactorMapper mappers;
    private final AuditService audit;

    public FactorService(EmissionFactorMapper mappers, AuditService audit) {
        this.mappers = mappers;
        this.audit = audit;
    }

    public List<EmissionFactor> list(String fuel, String scope, String reviewStatus) {
        return mappers.selectList(Wrappers.<EmissionFactor>lambdaQuery()
                .eq(fuel != null && !fuel.isBlank(), EmissionFactor::getFuel, fuel)
                .eq(scope != null && !scope.isBlank(), EmissionFactor::getScope, scope)
                .eq(reviewStatus != null && !reviewStatus.isBlank(), EmissionFactor::getReviewStatus, reviewStatus)
                .and(w -> w.isNull(EmissionFactor::getOrgId).or().eq(EmissionFactor::getOrgId, CurrentUser.orgId()))
                .orderByAsc(EmissionFactor::getFuel)
                .orderByDesc(EmissionFactor::getEffectiveFrom));
    }

    @Transactional
    public EmissionFactor create(FactorDtos.CreateReq req) {
        if (!SCOPES.contains(req.scope())) {
            throw BizException.validation("层级只能是 national / local / customer");
        }
        if (req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            throw BizException.validation("失效日期不能早于生效日期");
        }
        EmissionFactor f = new EmissionFactor();
        f.setCategory(req.category());
        f.setFuel(req.fuel());
        f.setUnit(req.unit());
        f.setFactorValue(req.value());
        f.setSourceDoc(req.sourceDoc());
        f.setScope(req.scope());
        f.setOrgId("customer".equals(req.scope()) ? CurrentUser.orgId() : null);
        f.setVersion(req.version());
        f.setReviewStatus("pending");
        f.setEffectiveFrom(req.effectiveFrom());
        f.setEffectiveTo(req.effectiveTo());
        f.setCreatedBy(CurrentUser.username());
        f.setCreatedAt(LocalDateTime.now());
        mappers.insert(f);
        audit.record("emission_factor", f.getId(), "create", null, f);
        return f;
    }

    @Transactional
    public EmissionFactor review(Long id, boolean approve) {
        EmissionFactor old = mappers.selectById(id);
        if (old == null) throw BizException.notFound("因子不存在");
        if ("customer".equals(old.getScope()) && !CurrentUser.orgId().equals(old.getOrgId())) {
            throw BizException.validation("不能审核其他企业的自定义因子");
        }
        EmissionFactor patch = new EmissionFactor();
        patch.setId(id);
        patch.setReviewStatus(approve ? "approved" : "rejected");
        mappers.updateById(patch);
        EmissionFactor now = mappers.selectById(id);
        audit.record("emission_factor", id, approve ? "approve" : "reject", old, now);
        return now;
    }

    /** 三级优先级：customer(本企业) > local > national；同层级取最新生效。 */
    public Optional<EmissionFactor> pick(String fuel, LocalDate periodStart, LocalDate periodEnd) {
        List<EmissionFactor> candidates = mappers.selectList(Wrappers.<EmissionFactor>lambdaQuery()
                .eq(EmissionFactor::getFuel, fuel)
                .eq(EmissionFactor::getReviewStatus, "approved")
                .le(EmissionFactor::getEffectiveFrom, periodEnd)
                .and(w -> w.isNull(EmissionFactor::getEffectiveTo).or().ge(EmissionFactor::getEffectiveTo, periodStart))
                .and(w -> w.isNull(EmissionFactor::getOrgId).or().eq(EmissionFactor::getOrgId, CurrentUser.orgId())));
        return candidates.stream().max(Comparator
                .comparingInt((EmissionFactor f) -> weight(f.getScope()))
                .thenComparing(EmissionFactor::getEffectiveFrom)
                .thenComparing(EmissionFactor::getId));
    }

    private int weight(String scope) {
        return switch (scope == null ? "" : scope) {
            case "customer" -> 3;
            case "local" -> 2;
            default -> 1;
        };
    }
}
