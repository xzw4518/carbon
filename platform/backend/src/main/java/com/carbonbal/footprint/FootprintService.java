package com.carbonbal.footprint;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FootprintService {
    private static final BigDecimal TOL = new BigDecimal("0.01");
    private static final List<String> BOUNDARIES = List.of("摇篮到大门", "摇篮到坟墓");

    private final ProductFootprintMapper mapper;
    private final AuditService audit;

    public FootprintService(ProductFootprintMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<ProductFootprint> list(int page, int size, String keyword,
                                             LocalDate from, LocalDate to, String boundary) {
        Page<ProductFootprint> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<ProductFootprint>lambdaQuery()
                .eq(ProductFootprint::getOrgId, CurrentUser.orgId())
                .eq(ProductFootprint::getDisabled, 0)
                .eq(boundary != null && !boundary.isBlank(), ProductFootprint::getBoundary, boundary)
                .ge(from != null, ProductFootprint::getDate, from)
                .le(to != null, ProductFootprint::getDate, to)
                .like(keyword != null && !keyword.isBlank(), ProductFootprint::getName, keyword)
                .orderByDesc(ProductFootprint::getDate)
                .orderByDesc(ProductFootprint::getId));
        return PageResult.of(p);
    }

    /** 单条详情：不存在或不属于本企业统一 404。 */
    public ProductFootprint detail(Long id) {
        ProductFootprint f = id == null ? null : mapper.selectOne(Wrappers.<ProductFootprint>lambdaQuery()
                .eq(ProductFootprint::getId, id)
                .eq(ProductFootprint::getOrgId, CurrentUser.orgId())
                .eq(ProductFootprint::getDisabled, 0));
        if (f == null) throw BizException.notFound("碳足迹记录不存在");
        return f;
    }

    @Transactional
    public ProductFootprint create(FootprintDtos.Req req) {
        validate(req);
        ProductFootprint f = new ProductFootprint();
        f.setOrgId(CurrentUser.orgId());
        apply(f, req);
        f.setStatus("已核算");
        f.setDisabled(0);
        f.setVersion(0);
        f.setCreatedBy(CurrentUser.username());
        f.setUpdatedBy(CurrentUser.username());
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        mapper.insert(f);
        audit.record("product_footprint", f.getId(), "create", null, f);
        return f;
    }

    @Transactional
    public ProductFootprint update(Long id, FootprintDtos.Req req) {
        ProductFootprint old = require(id);
        validate(req);
        int rows = mapper.update(null, Wrappers.<ProductFootprint>lambdaUpdate()
                .eq(ProductFootprint::getId, id)
                .eq(req.version() != null, ProductFootprint::getVersion, req.version())
                .set(ProductFootprint::getProductId, req.productId())
                .set(ProductFootprint::getName, req.name())
                .set(ProductFootprint::getUnit, req.unit())
                .set(ProductFootprint::getQty, req.qty())
                .set(ProductFootprint::getFootprint, req.footprint())
                .set(ProductFootprint::getDate, req.date())
                .set(ProductFootprint::getBoundary, req.boundary())
                .set(ProductFootprint::getStageRaw, nz(req.stageRaw()))
                .set(ProductFootprint::getStageTransport, nz(req.stageTransport()))
                .set(ProductFootprint::getStageMake, nz(req.stageMake()))
                .set(ProductFootprint::getRemark, req.remark())
                .set(ProductFootprint::getUpdatedBy, CurrentUser.username())
                .set(ProductFootprint::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            ProductFootprint latest = mapper.selectById(id);
            throw BizException.conflict("足迹记录已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        ProductFootprint now = mapper.selectById(id);
        audit.record("product_footprint", id, "update", old, now);
        return now;
    }

    @Transactional
    public void remove(Long id) {
        ProductFootprint old = require(id);
        mapper.update(null, Wrappers.<ProductFootprint>lambdaUpdate()
                .eq(ProductFootprint::getId, id).set(ProductFootprint::getDisabled, 1)
                .setSql("version = version + 1"));
        audit.record("product_footprint", id, "delete", old, null);
    }

    private ProductFootprint require(Long id) {
        ProductFootprint f = mapper.selectById(id);
        if (f == null || Integer.valueOf(1).equals(f.getDisabled())) throw BizException.notFound("足迹记录不存在");
        if (!f.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return f;
    }

    private void validate(FootprintDtos.Req req) {
        if (!BOUNDARIES.contains(req.boundary())) {
            throw BizException.validation("核算边界只能是 摇篮到大门 / 摇篮到坟墓");
        }
        BigDecimal sum = nz(req.stageRaw()).add(nz(req.stageTransport())).add(nz(req.stageMake()));
        if (sum.subtract(req.footprint()).abs().compareTo(TOL) > 0) {
            throw BizException.validation("分阶段之和 " + sum.toPlainString()
                    + " 与总足迹 " + req.footprint().toPlainString() + " 差值超过 0.01");
        }
    }

    private void apply(ProductFootprint f, FootprintDtos.Req r) {
        f.setProductId(r.productId());
        f.setName(r.name());
        f.setUnit(r.unit());
        f.setQty(r.qty());
        f.setFootprint(r.footprint());
        f.setDate(r.date());
        f.setBoundary(r.boundary());
        f.setStageRaw(nz(r.stageRaw()));
        f.setStageTransport(nz(r.stageTransport()));
        f.setStageMake(nz(r.stageMake()));
        f.setRemark(r.remark());
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
