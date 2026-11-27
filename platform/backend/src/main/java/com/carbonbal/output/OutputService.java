package com.carbonbal.output;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import com.carbonbal.common.PeriodGuard;
import com.carbonbal.product.Product;
import com.carbonbal.product.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class OutputService {
    private final ProductionValueMapper production;
    private final TotalValueMapper total;
    private final ProductMapper products;
    private final PeriodGuard periodGuard;
    private final AuditService audit;

    public OutputService(ProductionValueMapper production, TotalValueMapper total,
                         ProductMapper products, PeriodGuard periodGuard, AuditService audit) {
        this.production = production;
        this.total = total;
        this.products = products;
        this.periodGuard = periodGuard;
        this.audit = audit;
    }

    /* ---------- 产品产值 ---------- */

    public PageResult<ProductionValue> listProduction(int page, int size, String keyword, String from, String to) {
        Page<ProductionValue> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        production.selectPage(p, Wrappers.<ProductionValue>lambdaQuery()
                .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                .eq(ProductionValue::getDisabled, 0)
                .ge(from != null && !from.isBlank(), ProductionValue::getPeriod, from)
                .le(to != null && !to.isBlank(), ProductionValue::getPeriod, to)
                .like(keyword != null && !keyword.isBlank(), ProductionValue::getProductName, keyword)
                .orderByDesc(ProductionValue::getPeriod)
                .orderByDesc(ProductionValue::getId));
        return PageResult.of(p);
    }

    public ProductionValue getProduction(Long id) {
        return requireProduction(id);
    }

    @Transactional
    public ProductionValue createProduction(OutputDtos.ProductionReq req) {
        periodGuard.assertEditable(req.period());
        Product product = requireProduct(req.productId());
        assertUnique(req.period(), product.getId(), null);

        ProductionValue v = new ProductionValue();
        v.setOrgId(CurrentUser.orgId());
        v.setPeriod(req.period());
        v.setProductId(product.getId());
        v.setProductName(product.getName());
        v.setOutputValue(req.value());
        v.setUnit(req.unit());
        v.setQty(req.qty());
        v.setQtyUnit(req.qtyUnit());
        v.setRemark(req.remark());
        v.setDisabled(0);
        v.setVersion(0);
        v.setCreatedBy(CurrentUser.username());
        v.setUpdatedBy(CurrentUser.username());
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        production.insert(v);
        audit.record("production_value", v.getId(), "create", null, v);
        return v;
    }

    @Transactional
    public ProductionValue updateProduction(Long id, OutputDtos.ProductionReq req) {
        ProductionValue old = requireProduction(id);
        periodGuard.assertEditable(old.getPeriod());
        periodGuard.assertEditable(req.period());
        Product product = requireProduct(req.productId());
        assertUnique(req.period(), product.getId(), id);

        int rows = production.update(null, Wrappers.<ProductionValue>lambdaUpdate()
                .eq(ProductionValue::getId, id)
                .eq(req.version() != null, ProductionValue::getVersion, req.version())
                .set(ProductionValue::getPeriod, req.period())
                .set(ProductionValue::getProductId, product.getId())
                .set(ProductionValue::getProductName, product.getName())
                .set(ProductionValue::getOutputValue, req.value())
                .set(ProductionValue::getUnit, req.unit())
                .set(ProductionValue::getQty, req.qty())
                .set(ProductionValue::getQtyUnit, req.qtyUnit())
                .set(ProductionValue::getRemark, req.remark())
                .set(ProductionValue::getUpdatedBy, CurrentUser.username())
                .set(ProductionValue::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            ProductionValue latest = production.selectById(id);
            throw BizException.conflict("产值记录已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        ProductionValue now = production.selectById(id);
        audit.record("production_value", id, "update", old, now);
        return now;
    }

    @Transactional
    public void removeProduction(Long id) {
        ProductionValue old = requireProduction(id);
        periodGuard.assertEditable(old.getPeriod());
        production.update(null, Wrappers.<ProductionValue>lambdaUpdate()
                .eq(ProductionValue::getId, id)
                .set(ProductionValue::getDisabled, 1)
                .set(ProductionValue::getUpdatedBy, CurrentUser.username())
                .set(ProductionValue::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("production_value", id, "delete", old, null);
    }

    /* ---------- 生产总值 ---------- */

    public PageResult<TotalValue> listTotal(int page, int size, String keyword, String from, String to) {
        Page<TotalValue> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        total.selectPage(p, Wrappers.<TotalValue>lambdaQuery()
                .eq(TotalValue::getOrgId, CurrentUser.orgId())
                .eq(TotalValue::getDisabled, 0)
                .ge(from != null && !from.isBlank(), TotalValue::getPeriod, from)
                .le(to != null && !to.isBlank(), TotalValue::getPeriod, to)
                .like(keyword != null && !keyword.isBlank(), TotalValue::getName, keyword)
                .orderByDesc(TotalValue::getPeriod)
                .orderByDesc(TotalValue::getId));
        return PageResult.of(p);
    }

    public TotalValue getTotal(Long id) {
        return requireTotal(id);
    }

    @Transactional
    public TotalValue createTotal(OutputDtos.TotalReq req) {
        periodGuard.assertEditable(req.period());
        assertTotalUnique(req.period(), req.name(), null);

        TotalValue v = new TotalValue();
        v.setOrgId(CurrentUser.orgId());
        v.setPeriod(req.period());
        v.setName(req.name());

        v.setRemark(req.remark());
        v.setQty(req.qty());
        v.setQtyUnit(req.qtyUnit());
        v.setOutputValue(req.value());
        v.setUnit(req.unit());
        v.setDisabled(0);
        v.setVersion(0);
        v.setCreatedBy(CurrentUser.username());
        v.setUpdatedBy(CurrentUser.username());
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        total.insert(v);
        audit.record("total_value", v.getId(), "create", null, v);
        return v;
    }

    @Transactional
    public TotalValue updateTotal(Long id, OutputDtos.TotalReq req) {
        TotalValue old = requireTotal(id);
        periodGuard.assertEditable(old.getPeriod());
        periodGuard.assertEditable(req.period());
        assertTotalUnique(req.period(), req.name(), id);

        int rows = total.update(null, Wrappers.<TotalValue>lambdaUpdate()
                .eq(TotalValue::getId, id)
                .eq(req.version() != null, TotalValue::getVersion, req.version())
                .set(TotalValue::getPeriod, req.period())
                .set(TotalValue::getName, req.name())
                .set(TotalValue::getRemark, req.remark())
                .set(TotalValue::getQty, req.qty())
                .set(TotalValue::getQtyUnit, req.qtyUnit())
                .set(TotalValue::getOutputValue, req.value())
                .set(TotalValue::getUnit, req.unit())
                .set(TotalValue::getUpdatedBy, CurrentUser.username())
                .set(TotalValue::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            TotalValue latest = total.selectById(id);
            throw BizException.conflict("生产总值记录已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        TotalValue now = total.selectById(id);
        audit.record("total_value", id, "update", old, now);
        return now;
    }

    @Transactional
    public void removeTotal(Long id) {
        TotalValue old = requireTotal(id);
        periodGuard.assertEditable(old.getPeriod());
        total.update(null, Wrappers.<TotalValue>lambdaUpdate()
                .eq(TotalValue::getId, id)
                .set(TotalValue::getDisabled, 1)
                .set(TotalValue::getUpdatedBy, CurrentUser.username())
                .set(TotalValue::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("total_value", id, "delete", old, null);
    }

    /* ---------- 内部 ---------- */

    private ProductionValue requireProduction(Long id) {
        ProductionValue v = production.selectById(id);
        if (v == null || Integer.valueOf(1).equals(v.getDisabled())) throw BizException.notFound("产值记录不存在");
        if (!v.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return v;
    }

    private TotalValue requireTotal(Long id) {
        TotalValue v = total.selectById(id);
        if (v == null || Integer.valueOf(1).equals(v.getDisabled())) throw BizException.notFound("生产总值记录不存在");
        if (!v.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return v;
    }

    private Product requireProduct(Long productId) {
        Product p = products.selectById(productId);
        if (p == null || Integer.valueOf(1).equals(p.getDisabled())) throw BizException.validation("产品不存在或已删除");
        if (!p.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权引用其他企业的产品");
        return p;
    }

    private void assertUnique(String period, Long productId, Long excludeId) {
        Long dup = production.selectCount(Wrappers.<ProductionValue>lambdaQuery()
                .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                .eq(ProductionValue::getPeriod, period)
                .eq(ProductionValue::getProductId, productId)
                .eq(ProductionValue::getDisabled, 0)
                .ne(excludeId != null, ProductionValue::getId, excludeId));
        if (dup != null && dup > 0) {
            throw BizException.duplicate("该产品在该月份已有产值记录", period);
        }
    }

    private void assertTotalUnique(String period, String name, Long excludeId) {
        Long dup = total.selectCount(Wrappers.<TotalValue>lambdaQuery()
                .eq(TotalValue::getOrgId, CurrentUser.orgId())
                .eq(TotalValue::getPeriod, period)
                .eq(TotalValue::getName, name)
                .eq(TotalValue::getDisabled, 0)
                .ne(excludeId != null, TotalValue::getId, excludeId));
        if (dup != null && dup > 0) {
            throw BizException.duplicate("该名称在该月份已有生产总值记录", period);
        }
    }
}
