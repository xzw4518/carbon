package com.carbonbal.asset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TradeService {
    private final CarbonTradeMapper mapper;
    private final CarbonAssetMapper assets;
    private final AssetService assetService;
    private final AuditService audit;

    public TradeService(CarbonTradeMapper mapper, CarbonAssetMapper assets,
                        AssetService assetService, AuditService audit) {
        this.mapper = mapper;
        this.assets = assets;
        this.assetService = assetService;
        this.audit = audit;
    }

    public PageResult<CarbonTrade> list(int page, int size, Long assetId, String type, String assetType,
                                        LocalDate from, LocalDate to) {
        Page<CarbonTrade> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        List<Long> typeIds = null;
        if (assetType != null && !assetType.isBlank()) {
            typeIds = assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                            .eq(CarbonAsset::getOrgId, CurrentUser.orgId())
                            .eq(CarbonAsset::getType, assetType))
                    .stream().map(CarbonAsset::getId).toList();
            if (typeIds.isEmpty()) return new PageResult<>(0, List.of(), 1, (int) p.getSize());
        }
        mapper.selectPage(p, Wrappers.<CarbonTrade>lambdaQuery()
                .eq(CarbonTrade::getOrgId, CurrentUser.orgId())
                .eq(CarbonTrade::getDisabled, 0)
                .eq(assetId != null, CarbonTrade::getAssetId, assetId)
                .in(typeIds != null, CarbonTrade::getAssetId, typeIds)
                .eq(type != null && !type.isBlank(), CarbonTrade::getType, type)
                .ge(from != null, CarbonTrade::getTradeDate, from)
                .le(to != null, CarbonTrade::getTradeDate, to)
                .orderByDesc(CarbonTrade::getTradeDate)
                .orderByDesc(CarbonTrade::getId));
        return PageResult.of(p);
    }

    /** 购入增加库存，售出原子扣减；扣减失败即余额不足，返回 409。 */
    /** 单条详情：不存在或不属于本企业统一 404。 */
    public CarbonTrade detail(Long id) {
        CarbonTrade t = id == null ? null : mapper.selectOne(Wrappers.<CarbonTrade>lambdaQuery()
                .eq(CarbonTrade::getId, id)
                .eq(CarbonTrade::getOrgId, CurrentUser.orgId())
                .eq(CarbonTrade::getDisabled, 0));
        if (t == null) throw BizException.notFound("交易记录不存在");
        return t;
    }

    @Transactional
    public CarbonTrade create(AssetDtos.TradeReq req) {
        CarbonAsset asset = assetService.require(req.assetId());
        if (!List.of("购入", "售出").contains(req.type())) {
            throw BizException.validation("交易类型只能是 购入 / 售出");
        }
        BigDecimal qty = req.qty().setScale(4, RoundingMode.HALF_UP);
        int rows;
        if ("售出".equals(req.type())) {
            rows = assets.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                    .eq(CarbonAsset::getId, asset.getId())
                    .ge(CarbonAsset::getQty, qty)
                    .setSql("qty = qty - " + qty.toPlainString())
                    .setSql("version = version + 1"));
            if (rows == 0) {
                throw BizException.insufficient("可用数量不足，无法售出",
                        java.util.Map.of("available", asset.getQty(), "requested", qty));
            }
        } else {
            rows = assets.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                    .eq(CarbonAsset::getId, asset.getId())
                    .setSql("qty = qty + " + qty.toPlainString())
                    .setSql("version = version + 1"));
        }
        if (rows == 0) throw BizException.conflict("资产已被他人修改，请刷新后重试", null);

        CarbonTrade t = new CarbonTrade();
        t.setOrgId(CurrentUser.orgId());
        t.setAssetId(asset.getId());
        t.setAssetName(asset.getName());
        t.setType(req.type());
        t.setQty(qty);
        t.setPrice(req.price().setScale(4, RoundingMode.HALF_UP));
        t.setAmount(qty.multiply(req.price()).setScale(4, RoundingMode.HALF_UP));
        t.setParty(req.party());
        t.setUsePurpose(req.usePurpose());
        t.setTradeDate(req.tradeDate());
        t.setStatus("已成交");
        t.setDisabled(0);
        t.setVersion(0);
        t.setCreatedBy(CurrentUser.username());
        t.setCreatedAt(LocalDateTime.now());
        mapper.insert(t);

        CarbonAsset after = assets.selectById(asset.getId());
        audit.record("carbon_trade", t.getId(), "create",
                java.util.Map.of("assetQtyBefore", asset.getQty()),
                java.util.Map.of("assetQtyAfter", after.getQty(), "type", t.getType(),
                        "qty", t.getQty(), "amount", t.getAmount()));
        return t;
    }

    /** 软删交易并反向冲销库存；冲销时若库存不足（已再交易）则拒绝。 */
    @Transactional
    public void remove(Long id) {
        CarbonTrade t = mapper.selectById(id);
        if (t == null || Integer.valueOf(1).equals(t.getDisabled())) throw BizException.notFound("交易不存在");
        if (!t.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权操作其他企业的数据");

        int rows;
        if ("售出".equals(t.getType())) {
            rows = assets.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                    .eq(CarbonAsset::getId, t.getAssetId())
                    .setSql("qty = qty + " + t.getQty().toPlainString())
                    .setSql("version = version + 1"));
        } else {
            rows = assets.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                    .eq(CarbonAsset::getId, t.getAssetId())
                    .ge(CarbonAsset::getQty, t.getQty())
                    .setSql("qty = qty - " + t.getQty().toPlainString())
                    .setSql("version = version + 1"));
        }
        if (rows == 0) throw BizException.insufficient("当前库存不足以冲销该交易，无法删除", null);

        mapper.update(null, Wrappers.<CarbonTrade>lambdaUpdate()
                .eq(CarbonTrade::getId, id).set(CarbonTrade::getDisabled, 1)
                .setSql("version = version + 1"));
        audit.record("carbon_trade", id, "delete", t, null);
    }
}
