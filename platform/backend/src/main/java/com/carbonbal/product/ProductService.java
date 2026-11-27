package com.carbonbal.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.common.UniqueKeys;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ProductService {
    private final ProductMapper mapper;
    private final AuditService audit;

    public ProductService(ProductMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<Product> list(int page, int size, String keyword, String type, String cls) {
        Page<Product> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<Product>lambdaQuery()
                .eq(Product::getOrgId, CurrentUser.orgId())
                .eq(Product::getDisabled, 0)
                .eq(type != null && !type.isBlank(), Product::getType, type)
                .eq(cls != null && !cls.isBlank(), Product::getCls, cls)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Product::getName, keyword).or().like(Product::getCode, keyword))
                .orderByDesc(Product::getId));
        return PageResult.of(p);
    }

    /** 单条详情：不存在、已禁用或不属于本企业统一 404（不泄露存在性）。 */
    public Product detail(Long id) {
        Product p = id == null ? null : mapper.selectOne(Wrappers.<Product>lambdaQuery()
                .eq(Product::getId, id)
                .eq(Product::getOrgId, CurrentUser.orgId())
                .eq(Product::getDisabled, 0)
);
        if (p == null) throw BizException.notFound("产品不存在");
        return p;
    }

    @Transactional
    public Product create(MasterDataDtos.ProductReq req) {
        Long dup = mapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getOrgId, CurrentUser.orgId())
                .eq(Product::getCode, req.code()));
        if (dup != null && dup > 0) throw BizException.duplicate("产品编码已存在", req.code());

        Product p = new Product();
        p.setOrgId(CurrentUser.orgId());
        apply(p, req);
        p.setDisabled(0);
        p.setVersion(0);
        p.setCreatedBy(CurrentUser.username());
        p.setUpdatedBy(CurrentUser.username());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        audit.record("product", p.getId(), "create", null, p);
        return p;
    }

    @Transactional
    public Product update(Long id, MasterDataDtos.ProductReq req) {
        Product old = require(id);
        int rows = mapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, id)
                .eq(req.version() != null, Product::getVersion, req.version())
                .set(Product::getName, req.name())
                .set(Product::getRemark, req.remark())
                .set(Product::getCode, req.code())
                .set(Product::getModel, req.model())
                .set(Product::getType, req.type())
                .set(Product::getCls, req.cls())
                .set(Product::getUnit, req.unit())
                .set(Product::getL1, req.l1())
                .set(Product::getL2, req.l2())
                .set(Product::getL3, req.l3())
                .set(Product::getUpdatedBy, CurrentUser.username())
                .set(Product::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Product latest = mapper.selectById(id);
            throw BizException.conflict("产品已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        Product now = mapper.selectById(id);
        audit.record("product", id, "update", old, now);
        return now;
    }

    @Transactional
    public void remove(Long id) {
        Product old = require(id);
        mapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, id).set(Product::getDisabled, 1)
                // 释放编码，否则同编码无法重建（唯一索引不含 disabled）
                .set(Product::getCode, UniqueKeys.release(old.getCode(), id, 64))
                .setSql("version = version + 1"));
        audit.record("product", id, "delete", old, null);
    }

    Product require(Long id) {
        Product p = mapper.selectById(id);
        if (p == null || Integer.valueOf(1).equals(p.getDisabled())) throw BizException.notFound("产品不存在");
        if (!p.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return p;
    }

    private void apply(Product p, MasterDataDtos.ProductReq r) {
        p.setName(r.name());

        p.setRemark(r.remark());
        p.setCode(r.code());
        p.setModel(r.model());
        p.setType(r.type());
        p.setCls(r.cls());
        p.setUnit(r.unit());
        p.setL1(r.l1());
        p.setL2(r.l2());
        p.setL3(r.l3());
    }
}
