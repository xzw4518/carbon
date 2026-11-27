package com.carbonbal.asset;

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
public class AssetService {
    private final CarbonAssetMapper mapper;
    private final AuditService audit;

    public AssetService(CarbonAssetMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<CarbonAsset> list(int page, int size, String keyword, String type, Integer year) {
        Page<CarbonAsset> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<CarbonAsset>lambdaQuery()
                .eq(CarbonAsset::getOrgId, CurrentUser.orgId())
                .eq(CarbonAsset::getDisabled, 0)
                .eq(type != null && !type.isBlank(), CarbonAsset::getType, type)
                .eq(year != null, CarbonAsset::getAssetYear, year)
                .like(keyword != null && !keyword.isBlank(), CarbonAsset::getName, keyword)
                .orderByDesc(CarbonAsset::getId));
        return PageResult.of(p);
    }

    @Transactional
    public CarbonAsset create(AssetDtos.AssetReq req) {
        CarbonAsset a = new CarbonAsset();
        a.setOrgId(CurrentUser.orgId());
        a.setName(req.name());

        a.setRemark(req.remark());
        a.setType(req.type());
        a.setQty(req.qty());
        a.setUnit(req.unit());
        a.setAssetYear(req.year());
        a.setStatus(req.status() == null ? "正常" : req.status());
        a.setExpireDate(req.expireDate());
        a.setDisabled(0);
        a.setVersion(0);
        a.setCreatedBy(CurrentUser.username());
        a.setUpdatedBy(CurrentUser.username());
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.insert(a);
        audit.record("carbon_asset", a.getId(), "create", null, a);
        return a;
    }

    @Transactional
    public CarbonAsset update(Long id, AssetDtos.AssetReq req) {
        CarbonAsset old = require(id);
        int rows = mapper.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                .eq(CarbonAsset::getId, id)
                .eq(req.version() != null, CarbonAsset::getVersion, req.version())
                .set(CarbonAsset::getName, req.name())
                .set(CarbonAsset::getRemark, req.remark())
                .set(CarbonAsset::getType, req.type())
                .set(CarbonAsset::getQty, req.qty())
                .set(CarbonAsset::getUnit, req.unit())
                .set(CarbonAsset::getAssetYear, req.year())
                .set(CarbonAsset::getStatus, req.status())
                .set(CarbonAsset::getExpireDate, req.expireDate())
                .set(CarbonAsset::getUpdatedBy, CurrentUser.username())
                .set(CarbonAsset::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            CarbonAsset latest = mapper.selectById(id);
            throw BizException.conflict("资产已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        CarbonAsset now = mapper.selectById(id);
        audit.record("carbon_asset", id, "update", old, now);
        return now;
    }

    @Transactional
    public void remove(Long id) {
        CarbonAsset old = require(id);
        mapper.update(null, Wrappers.<CarbonAsset>lambdaUpdate()
                .eq(CarbonAsset::getId, id).set(CarbonAsset::getDisabled, 1)
                // 释放名称，否则同名称同年度无法重建（唯一索引不含 disabled）
                .set(CarbonAsset::getName, UniqueKeys.release(old.getName(), id, 120))
                .setSql("version = version + 1"));
        audit.record("carbon_asset", id, "delete", old, null);
    }

    /** 单条详情：不存在、已禁用或跨企业统一 404。 */
    public CarbonAsset detail(Long id) {
        CarbonAsset a = id == null ? null : mapper.selectById(id);
        if (a == null || Integer.valueOf(1).equals(a.getDisabled())
                || !CurrentUser.orgId().equals(a.getOrgId())) {
            throw BizException.notFound("资产不存在");
        }
        return a;
    }

    public CarbonAsset require(Long id) {
        CarbonAsset a = mapper.selectById(id);
        if (a == null || Integer.valueOf(1).equals(a.getDisabled())) throw BizException.notFound("资产不存在");
        if (!a.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return a;
    }
}
