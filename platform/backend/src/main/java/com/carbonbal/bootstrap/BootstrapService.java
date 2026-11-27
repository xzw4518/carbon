package com.carbonbal.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.accounting.AccountingPeriod;
import com.carbonbal.accounting.AccountingPeriodMapper;
import com.carbonbal.alert.AlertEvent;
import com.carbonbal.alert.AlertEventMapper;
import com.carbonbal.alert.AlertRecord;
import com.carbonbal.alert.AlertRecordMapper;
import com.carbonbal.alert.AlertRule;
import com.carbonbal.alert.AlertRuleMapper;
import com.carbonbal.analysis.AnalysisDtos;
import com.carbonbal.analysis.AnalysisService;
import com.carbonbal.asset.CarbonAsset;
import com.carbonbal.asset.CarbonAssetMapper;
import com.carbonbal.asset.CarbonTrade;
import com.carbonbal.asset.CarbonTradeMapper;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.EnergyMath;
import com.carbonbal.device.Device;
import com.carbonbal.device.DeviceMapper;
import com.carbonbal.device.DeviceReading;
import com.carbonbal.device.DeviceReadingMapper;
import com.carbonbal.energy.EnergyMgmt;
import com.carbonbal.energy.EnergyMgmtMapper;
import com.carbonbal.energy.Retrofit;
import com.carbonbal.energy.RetrofitMapper;
import com.carbonbal.evidence.Evidence;
import com.carbonbal.evidence.EvidenceMapper;
import com.carbonbal.evidence.ThirdPartyReport;
import com.carbonbal.evidence.ThirdPartyReportMapper;
import com.carbonbal.facility.Facility;
import com.carbonbal.facility.FacilityMapper;
import com.carbonbal.file.FileObject;
import com.carbonbal.file.FileService;
import com.carbonbal.footprint.ProductFootprint;
import com.carbonbal.footprint.ProductFootprintMapper;
import com.carbonbal.org.Org;
import com.carbonbal.org.OrgMapper;
import com.carbonbal.output.ProductionValue;
import com.carbonbal.output.ProductionValueMapper;
import com.carbonbal.output.TotalValue;
import com.carbonbal.output.TotalValueMapper;
import com.carbonbal.product.Product;
import com.carbonbal.product.ProductMapper;
import com.carbonbal.product.Supplier;
import com.carbonbal.product.SupplierMapper;
import com.carbonbal.report.Report;
import com.carbonbal.report.ReportMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 前端快照：把后端数据整理成原型 data.js 的结构，供 /app/ 一次性加载。
 * 只读；写操作仍走各域接口。
 *
 * <p><b>技术债（P2-5，v1.1 拆分边界）</b>：本类已超过 800 行，承担了"租户上下文 + 20 余张表投影 +
 * 页面权限裁剪 + 图表聚合"四类职责。已登记拆分方案：按页面域拆成
 * {@code BootstrapOrgService}（org/quota/tenant）、{@code BootstrapLedgerService}（product/supplier/
 * footprint/asset/device 等台账投影）、{@code BootstrapInsightService}（kpi/monthly/todos/energyMix），
 * {@code BootstrapService} 只保留编排与 {@code filterByPages}。
 * <b>约束：本轮及之后的新代码不得继续堆入本类</b>，新增字段请放入对应子服务。
 */
@Service
public class BootstrapService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] COLORS = {"#0f7a5a", "#f59e0b", "#64748b", "#94a3b8", "#cbd5e1"};

    private final OrgMapper orgs;
    private final ProductMapper products;
    private final SupplierMapper suppliers;
    private final ProductionValueMapper production;
    private final TotalValueMapper totals;
    private final ProductFootprintMapper footprints;
    private final CarbonAssetMapper assets;
    private final CarbonTradeMapper trades;
    private final EvidenceMapper evidences;
    private final ThirdPartyReportMapper thirdReports;
    private final FacilityMapper facilities;
    private final RetrofitMapper retrofits;
    private final EnergyMgmtMapper energyMgmt;
    private final DeviceMapper devices;
    private final DeviceReadingMapper readings;
    private final AccountingPeriodMapper periods;
    private final AlertRuleMapper alertRules;
    private final AlertRecordMapper alertRecords;
    private final AlertEventMapper alertEvents;
    private final ReportMapper reports;
    private final com.carbonbal.quota.QuotaYearMapper quotaYears;
    private final FileService files;
    private final AnalysisService analysis;

    public BootstrapService(OrgMapper orgs, ProductMapper products, SupplierMapper suppliers,
                            ProductionValueMapper production, TotalValueMapper totals,
                            ProductFootprintMapper footprints, CarbonAssetMapper assets, CarbonTradeMapper trades,
                            EvidenceMapper evidences, ThirdPartyReportMapper thirdReports,
                            FacilityMapper facilities, RetrofitMapper retrofits, EnergyMgmtMapper energyMgmt,
                            DeviceMapper devices, DeviceReadingMapper readings, AccountingPeriodMapper periods,
                            AlertRuleMapper alertRules, AlertRecordMapper alertRecords, AlertEventMapper alertEvents,
                            ReportMapper reports, com.carbonbal.quota.QuotaYearMapper quotaYears,
                            FileService files, AnalysisService analysis) {
        this.orgs = orgs;
        this.products = products;
        this.suppliers = suppliers;
        this.production = production;
        this.totals = totals;
        this.footprints = footprints;
        this.assets = assets;
        this.trades = trades;
        this.evidences = evidences;
        this.thirdReports = thirdReports;
        this.facilities = facilities;
        this.retrofits = retrofits;
        this.energyMgmt = energyMgmt;
        this.devices = devices;
        this.readings = readings;
        this.periods = periods;
        this.alertRules = alertRules;
        this.alertRecords = alertRecords;
        this.alertEvents = alertEvents;
        this.reports = reports;
        this.quotaYears = quotaYears;
        this.files = files;
        this.analysis = analysis;
    }

    public Map<String, Object> snapshot(Integer year) {
        int y = year == null ? YearMonth.now().getYear() : year;
        Long org = CurrentUser.orgId();
        Map<String, Object> db = new LinkedHashMap<>();

        Org o = orgs.selectById(org);
        db.put("org", orgView(o));
        db.put("products", productRows(org));
        db.put("suppliers", supplierRows(org));


        db.put("footprint", footprintRows(org));
        db.put("assets", assetRows(org));




        db.put("facilities", facilityTree(org));


        db.put("devices", deviceRows(org));

        db.put("alertRules", ruleRows(org));
        db.put("alertRecords", recordRows(org));
        db.put("warningEvents", eventRows(org));
        db.put("reports", reportRows(org));
        db.put("accounting", accountingRows(org, y));
        db.put("accountingLines", Map.of());

        AnalysisDtos.Overview ov = analysis.overview(y);
        db.put("kpi", kpi(ov));
        db.put("monthly", monthly(ov));
        db.put("todos", todos(ov));
        db.put("energyMix", mix(y));
        db.put("quota", quota(org, y, ov));
        return filterByPages(db);
    }

    /** 按页面权限裁剪快照：无权页面的数据不下发，避免“藏了菜单却能看到数据”。 */
    private Map<String, Object> filterByPages(Map<String, Object> db) {
        Map<String, String> keyPage = Map.ofEntries(
                Map.entry("products", "product.list"),
                Map.entry("suppliers", "supplier.list"),
                Map.entry("footprint", "footprint.list"),
                Map.entry("assets", "asset.list"),
                Map.entry("facilities", "facility.list"),
                Map.entry("devices", "device.list"),
                Map.entry("alertRules", "alert.setting"),
                Map.entry("alertRecords", "alert.record"),
                Map.entry("warningEvents", "alert.event"),
                Map.entry("reports", "report.carbon"),
                Map.entry("accounting", "accounting.list"),
                Map.entry("accountingLines", "accounting.list"),
                Map.entry("quota", "quota.list"));
        keyPage.forEach((k, page) -> {
            if (!com.carbonbal.auth.PageAccess.allows(page)) db.remove(k);
        });
        return db;
    }

    private Map<String, Object> orgView(Org o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o == null) return m;
        m.put("name", o.getName());
        m.put("short", or(o.getShortName(), o.getName()));
        m.put("code", o.getCode());
        m.put("industry", o.getIndustry());
        m.put("scale", o.getScale());
        m.put("region", o.getRegion());
        m.put("contact", o.getContact());
        m.put("phone", o.getPhone());
        m.put("joined", o.getCreatedAt() == null ? "" : o.getCreatedAt().format(DATE));
        m.put("legalPerson", o.getLegalPerson());
        m.put("regDate", o.getRegDate() == null ? "" : o.getRegDate().toString());
        m.put("regCapital", o.getRegCapital());
        m.put("email", o.getEmail());
        m.put("intro", o.getIntro());
        m.put("orgType", o.getOrgType());
        m.put("remark", o.getRemark());
        m.put("version", o.getVersion());
        m.put("logoFileId", o.getLogoFileId());
        m.put("licenseFileId", o.getLicenseFileId());
        m.put("expireAt", o.getExpireAt() == null ? "" : o.getExpireAt().toLocalDate().toString());
        return m;
    }

    private List<Map<String, Object>> productRows(Long org) {
        return products.selectList(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getOrgId, org).eq(Product::getDisabled, 0)
                        .orderByAsc(Product::getId))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "PD-" + (1000 + p.getId()));
                    m.put("dbId", p.getId());
                    m.put("version", p.getVersion());
                    m.put("name", p.getName());
                    m.put("code", p.getCode());
                    m.put("model", p.getModel());
                    m.put("type", p.getType());
                    m.put("cls", p.getCls());
                    m.put("unit", p.getUnit());
                    m.put("l1", p.getL1());
                    m.put("l2", p.getL2());
                    m.put("l3", p.getL3());
                    m.put("at", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().format(DATE));
                    m.put("disabled", false);
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> supplierRows(Long org) {
        return suppliers.selectList(Wrappers.<Supplier>lambdaQuery()
                        .eq(Supplier::getOrgId, org).eq(Supplier::getDisabled, 0)
                        .orderByAsc(Supplier::getSort))
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "SP-" + s.getId());
                    m.put("dbId", s.getId());
                    m.put("version", s.getVersion());
                    m.put("parentId", s.getParentId());
                    m.put("name", s.getName());
                    m.put("product", s.getProduct());
                    m.put("unit", s.getUnit());
                    m.put("fpUnit", s.getFpUnit());
                    m.put("footprint", s.getFootprint());
                    m.put("date", s.getDate() == null ? "" : s.getDate().toString());
                    m.put("target", s.getTarget());
                    m.put("level", s.getLevel());
                    m.put("sort", s.getSort());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> productionRows(Long org) {
        return production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                        .eq(ProductionValue::getOrgId, org).eq(ProductionValue::getDisabled, 0)
                        .orderByDesc(ProductionValue::getPeriod).orderByDesc(ProductionValue::getId))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "PV-" + v.getId());
                    m.put("dbId", v.getId());
                    m.put("version", v.getVersion());
                    m.put("period", v.getPeriod());
                    m.put("product", v.getProductName());
                    m.put("value", v.getOutputValue());
                    m.put("unit", v.getUnit());
                    m.put("qty", v.getQty());
                    m.put("qtyUnit", v.getQtyUnit());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> totalRows(Long org) {
        return totals.selectList(Wrappers.<TotalValue>lambdaQuery()
                        .eq(TotalValue::getOrgId, org).eq(TotalValue::getDisabled, 0)
                        .orderByDesc(TotalValue::getPeriod).orderByDesc(TotalValue::getId))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "TV-" + v.getId());
                    m.put("dbId", v.getId());
                    m.put("version", v.getVersion());
                    m.put("period", v.getPeriod());
                    m.put("name", v.getName());
                    m.put("qty", v.getQty());
                    m.put("qtyUnit", v.getQtyUnit());
                    m.put("value", v.getOutputValue());
                    m.put("unit", v.getUnit());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> footprintRows(Long org) {
        return footprints.selectList(Wrappers.<ProductFootprint>lambdaQuery()
                        .eq(ProductFootprint::getOrgId, org).eq(ProductFootprint::getDisabled, 0)
                        .orderByDesc(ProductFootprint::getDate))
                .stream().map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "P-" + f.getId());
                    m.put("dbId", f.getId());
                    m.put("name", f.getName());
                    m.put("unit", f.getUnit());
                    m.put("qty", f.getQty());
                    m.put("footprint", f.getFootprint());
                    m.put("date", f.getDate() == null ? "" : f.getDate().toString());
                    m.put("boundary", f.getBoundary());
                    m.put("remark", f.getRemark());
                    Map<String, Object> stage = new LinkedHashMap<>();
                    stage.put("raw", f.getStageRaw() == null ? BigDecimal.ZERO : f.getStageRaw());
                    stage.put("transport", f.getStageTransport() == null ? BigDecimal.ZERO : f.getStageTransport());
                    stage.put("make", f.getStageMake() == null ? BigDecimal.ZERO : f.getStageMake());
                    m.put("stage", stage);
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> assetRows(Long org) {
        return assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                        .eq(CarbonAsset::getOrgId, org).eq(CarbonAsset::getDisabled, 0)
                        .orderByAsc(CarbonAsset::getId))
                .stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "AS-" + String.format("%03d", a.getId()));
                    m.put("dbId", a.getId());
                    m.put("version", a.getVersion());
                    m.put("name", a.getName());
                    m.put("qty", a.getQty());
                    m.put("unit", a.getUnit());
                    m.put("type", a.getType());
                    m.put("year", a.getAssetYear());
                    m.put("status", a.getStatus());
                    m.put("disabled", false);
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> ccerRows(Long org) {
        return assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                        .eq(CarbonAsset::getOrgId, org).eq(CarbonAsset::getDisabled, 0)
                        .eq(CarbonAsset::getType, "信用资产"))
                .stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "CC-" + String.format("%03d", a.getId()));
                    m.put("dbId", a.getId());
                    m.put("version", a.getVersion());
                    m.put("name", a.getName());
                    m.put("qty", a.getQty());
                    m.put("unit", a.getUnit());
                    m.put("type", "信用资产");
                    m.put("status", "已备案");
                    m.put("year", a.getAssetYear());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> tradeRows(Long org) {
        return trades.selectList(Wrappers.<CarbonTrade>lambdaQuery()
                        .eq(CarbonTrade::getOrgId, org).eq(CarbonTrade::getDisabled, 0)
                        .orderByDesc(CarbonTrade::getTradeDate))
                .stream().map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "TR-" + t.getId());
                    m.put("dbId", t.getId());
                    m.put("assetId", t.getAssetId());
                    m.put("asset", t.getAssetName());
                    m.put("qty", t.getQty());
                    m.put("price", t.getPrice());
                    m.put("amount", t.getAmount());
                    m.put("type", t.getType());
                    m.put("party", t.getParty());
                    m.put("use", t.getUsePurpose());
                    m.put("date", t.getTradeDate() == null ? "" : t.getTradeDate().toString());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> evidenceRows(Long org) {
        List<Evidence> list = evidences.selectList(Wrappers.<Evidence>lambdaQuery()
                .eq(Evidence::getOrgId, org).eq(Evidence::getDisabled, 0)
                .orderByDesc(Evidence::getAtDate));
        Map<String, List<FileObject>> fileMap = new HashMap<>();
        if (!list.isEmpty()) {
            files.byBizIds("evidence", list.stream().map(e -> String.valueOf(e.getId())).toList())
                    .forEach(f -> fileMap.computeIfAbsent(f.getBizId(), k -> new ArrayList<>()).add(f));
        }
        return list.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "EV-" + e.getId());
            m.put("dbId", e.getId());
            m.put("version", e.getVersion());
            m.put("name", e.getName());
            m.put("code", e.getCode());
            m.put("desc", e.getDescription());
            m.put("valid", validity(e.getValidFrom(), e.getValidTo()));
            m.put("at", e.getAtDate() == null ? "" : e.getAtDate().toString());
            m.put("files", fileMap.getOrDefault(String.valueOf(e.getId()), List.of())
                    .stream().map(FileObject::getName).toList());
            m.put("fileIds", fileMap.getOrDefault(String.valueOf(e.getId()), List.of())
                    .stream().map(FileObject::getId).toList());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> thirdRows(Long org) {
        List<ThirdPartyReport> list = thirdReports.selectList(Wrappers.<ThirdPartyReport>lambdaQuery()
                .eq(ThirdPartyReport::getOrgId, org).eq(ThirdPartyReport::getDisabled, 0)
                .orderByDesc(ThirdPartyReport::getAtDate));
        Map<Long, FileObject> fm = new HashMap<>();
        List<Long> ids = list.stream().map(ThirdPartyReport::getFileId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) files.byIds(ids).forEach(f -> fm.put(f.getId(), f));
        return list.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "TP-" + r.getId());
            m.put("dbId", r.getId());
            m.put("version", r.getVersion());
            m.put("name", r.getName());
            m.put("desc", r.getDescription());
            FileObject f = fm.get(r.getFileId());
            m.put("file", f == null ? "" : f.getName());
            m.put("fileId", f == null ? null : f.getId());
            m.put("valid", validity(r.getValidFrom(), r.getValidTo()));
            m.put("at", r.getAtDate() == null ? "" : r.getAtDate().toString());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> facilityTree(Long org) {
        List<Facility> all = facilities.selectList(Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, org).eq(Facility::getDisabled, 0)
                .orderByAsc(Facility::getTreeLevel).orderByAsc(Facility::getSortNo));
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Facility f : all) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", or(f.getCode(), "F" + f.getId()));
            n.put("dbId", f.getId());
            n.put("version", f.getVersion());
            n.put("name", f.getName());
            n.put("code", f.getCode());
            n.put("parentId", f.getParentId());
            n.put("level", f.getTreeLevel());
            n.put("sort", f.getSortNo());
            n.put("remark", f.getRemark());
            n.put("children", new ArrayList<Map<String, Object>>());
            nodes.put(f.getId(), n);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Facility f : all) {
            Map<String, Object> n = nodes.get(f.getId());
            Map<String, Object> parent = f.getParentId() == null ? null : nodes.get(f.getParentId());
            if (parent == null) roots.add(n);
            else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(n);
            }
        }
        return roots;
    }

    private List<Map<String, Object>> retrofitRows(Long org) {
        return retrofits.selectList(Wrappers.<Retrofit>lambdaQuery()
                        .eq(Retrofit::getOrgId, org).eq(Retrofit::getDisabled, 0)
                        .orderByDesc(Retrofit::getProjectYear).orderByDesc(Retrofit::getId))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "RP-" + r.getId());
                    m.put("dbId", r.getId());
                    m.put("version", r.getVersion());
                    m.put("year", r.getProjectYear());
                    m.put("name", r.getName());
                    m.put("invest", r.getInvest());
                    m.put("investUnit", r.getInvestUnit());
                    m.put("saving", r.getSaving());
                    m.put("savingUnit", r.getSavingUnit());
                    m.put("contract", r.getContract());
                    m.put("done", r.getDone());
                    m.put("at", r.getAtDate() == null ? "" : r.getAtDate().toString());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> energyMgmtRows(Long org) {
        return energyMgmt.selectList(Wrappers.<EnergyMgmt>lambdaQuery()
                        .eq(EnergyMgmt::getOrgId, org).eq(EnergyMgmt::getDisabled, 0)
                        .orderByDesc(EnergyMgmt::getAtDate))
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "EM-" + e.getId());
                    m.put("dbId", e.getId());
                    m.put("version", e.getVersion());
                    m.put("at", e.getAtDate() == null ? "" : e.getAtDate().toString());
                    m.put("org", e.getOrgName());
                    m.put("duty", e.getDuty());
                    m.put("team", e.getTeam());
                    m.put("post", e.getPost());
                    m.put("charge", e.getCharge());
                    m.put("filler", e.getFiller());
                    m.put("system", e.getSystemName());
                    m.put("report", e.getReport());
                    m.put("record", e.getFiling());
                    m.put("instName", e.getInstName());
                    m.put("stat", e.getStat());
                    m.put("promo", e.getPromo());
                    m.put("training", e.getTraining());
                    m.put("subsidy", e.getSubsidy());
                    m.put("contact", e.getContact());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> deviceRows(Long org) {
        Map<Long, String> facNames = new HashMap<>();
        facilities.selectList(Wrappers.<Facility>lambdaQuery().eq(Facility::getOrgId, org))
                .forEach(f -> facNames.put(f.getId(), f.getName()));
        return devices.selectList(Wrappers.<Device>lambdaQuery()
                        .eq(Device::getOrgId, org).eq(Device::getDisabled, false)
                        .orderByAsc(Device::getCode))
                .stream().map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dbId", d.getId());
                    m.put("name", d.getName());
                    m.put("code", d.getCode());
                    m.put("energyType", d.getEnergyType());
                    m.put("unit", d.getUnit());
                    m.put("status", d.getStatus());
                    m.put("facilityId", d.getFacilityId());
                    m.put("facilityName", facNames.get(d.getFacilityId()));
                    m.put("deviceType", d.getDeviceType());
                    m.put("level", d.getLevel());
                    m.put("remark", d.getRemark());
                    m.put("version", d.getVersion());
                    m.put("disabled", d.getDisabled());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> deviceDataRows(Long org) {
        List<Device> devs = devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, org));
        Map<Long, Device> dm = new HashMap<>();
        devs.forEach(d -> dm.put(d.getId(), d));
        return readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                        .eq(DeviceReading::getOrgId, org)
                        .orderByDesc(DeviceReading::getTs)
                        .last("LIMIT 30"))
                .stream().map(r -> {
                    Device d = dm.get(r.getDeviceId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "DD-" + r.getId());
                    m.put("dbId", r.getId());
                    m.put("version", r.getVersion());
                    m.put("deviceId", r.getDeviceId());
                    m.put("device", d == null ? "" : d.getName());
                    m.put("date", r.getTs() == null ? "" : r.getTs().format(MIN));
                    m.put("qty", r.getQty());
                    m.put("unit", r.getUnit());
                    m.put("rate", r.getRate());
                    m.put("source", r.getSource());
                    m.put("status", r.getStatus());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> ruleRows(Long org) {
        return alertRules.selectList(Wrappers.<AlertRule>lambdaQuery()
                        .eq(AlertRule::getOrgId, org).eq(AlertRule::getDisabled, 0)
                        .orderByDesc(AlertRule::getId))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "R" + r.getId());
                    m.put("dbId", r.getId());
                    m.put("version", r.getVersion());
                    m.put("name", r.getName());
                    m.put("metric", r.getMetric());
                    m.put("op", r.getOp());
                    m.put("threshold", r.getThreshold());
                    m.put("unit", r.getUnit());
                    m.put("cool", r.getCool());
                    m.put("level", r.getLevel());
                    m.put("enabled", Integer.valueOf(1).equals(r.getEnabled()));
                    m.put("remark", r.getRemark());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> recordRows(Long org) {
        return alertRecords.selectList(Wrappers.<AlertRecord>lambdaQuery()
                        .eq(AlertRecord::getOrgId, org).eq(AlertRecord::getDisabled, 0)
                        .orderByDesc(AlertRecord::getOccurredAt)
                        .last("LIMIT 50"))
                .stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "A-" + a.getId());
                    m.put("dbId", a.getId());
                    m.put("title", a.getTitle());
                    m.put("level", a.getLevel());
                    m.put("value", a.getMetricValue() == null ? "" :
                            a.getMetricValue().stripTrailingZeros().toPlainString() + or(a.getUnit(), ""));
                    m.put("limit", a.getThreshold() == null ? "" : a.getThreshold().stripTrailingZeros().toPlainString());
                    m.put("at", a.getOccurredAt() == null ? "" : a.getOccurredAt().format(MIN));
                    m.put("status", a.getStatus());
                    m.put("device", a.getDeviceCode());
                    m.put("read", Integer.valueOf(1).equals(a.getIsRead()));
                    m.put("metric", a.getMetric());
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> eventRows(Long org) {
        return alertEvents.selectList(Wrappers.<AlertEvent>lambdaQuery()
                        .eq(AlertEvent::getOrgId, org).eq(AlertEvent::getDisabled, 0)
                        .orderByAsc(AlertEvent::getId))
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", "WE-" + e.getId());
                    m.put("dbId", e.getId());
                    m.put("version", e.getVersion());
                    m.put("code", "event." + e.getId());
                    m.put("name", e.getName());
                    m.put("type", e.getType());
                    m.put("builtin", Integer.valueOf(1).equals(e.getBuiltin()));
                    m.put("enabled", Integer.valueOf(1).equals(e.getEnabled()));
                    return m;
                }).toList();
    }

    /**
     * 报告列表行（P1-6）：<b>只投影元数据列</b>，绝不带 {@code report.content}（PDF 二进制）。
     *
     * <p>历史实现用 {@code selectList} 全字段查询，每次 {@code /api/bootstrap} 都把该租户所有报告的
     * PDF 字节读进堆内存再丢弃；报告越多响应越慢、内存越胀，而前端只用 7 个元数据字段。
     * 这里改用 MyBatis-Plus 列投影，SQL 里不再出现 {@code content}。
     */
    private List<Map<String, Object>> reportRows(Long org) {
        return reports.selectList(Wrappers.<Report>lambdaQuery()
                        .select(Report::getId, Report::getReportType, Report::getPeriodLabel,
                                Report::getFileName, Report::getFileSize, Report::getGeneratedAt)
                        .eq(Report::getOrgId, org).eq(Report::getDisabled, 0)
                        .orderByDesc(Report::getId))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String kind = switch (or(r.getReportType(), "carbon")) {
                        case "energy" -> "energy";
                        case "footprint" -> "footprint";
                        default -> "emission";
                    };
                    m.put("id", "R-" + r.getId());
                    m.put("dbId", r.getId());
                    m.put("name", r.getFileName().replace(".pdf", ""));
                    m.put("kind", kind);
                    m.put("period", r.getPeriodLabel());
                    m.put("size", size(r.getFileSize()));
                    m.put("at", r.getGeneratedAt() == null ? "" : r.getGeneratedAt().format(MIN));
                    m.put("status", "ready");
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> accountingRows(Long org, int year) {
        List<AccountingPeriod> list = periods.selectList(Wrappers.<AccountingPeriod>lambdaQuery()
                .eq(AccountingPeriod::getOrgId, org)
                .orderByDesc(AccountingPeriod::getPeriod)
                .last("LIMIT 24"));
        Map<String, BigDecimal> energy = energyByPeriod(org);
        return list.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "AC-" + p.getPeriod());
            m.put("period", p.getPeriod());
            m.put("scope1", p.getScope1());
            m.put("scope2", p.getScope2());
            m.put("total", p.getTotal());
            m.put("energy", energy.getOrDefault(p.getPeriod(), BigDecimal.ZERO));
            m.put("status", switch (or(p.getStatus(), "draft")) {
                case "locked", "submitted" -> "done";
                case "calculated" -> "review";
                default -> "draft";
            });
            m.put("rawStatus", or(p.getStatus(), "draft"));
            m.put("calcVersion", p.getCalcVersion());
            m.put("version", p.getVersion());
            m.put("by", p.getLockedBy() == null ? "" : p.getLockedBy());
            m.put("at", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().format(MIN));
            return m;
        }).toList();
    }

    private Map<String, BigDecimal> energyByPeriod(Long org) {
        List<Device> devs = devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, org));
        Map<Long, Device> dm = new HashMap<>();
        devs.forEach(d -> dm.put(d.getId(), d));
        Map<String, BigDecimal> out = new HashMap<>();
        readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                        .eq(DeviceReading::getOrgId, org)
                        .eq(DeviceReading::getStatus, "正常"))
                .forEach(r -> {
                    Device d = dm.get(r.getDeviceId());
                    if (d == null || !EnergyMath.supported(d.getEnergyType())) return;
                    String period = YearMonth.from(r.getTs()).toString();
                    out.merge(period, EnergyMath.tce(d.getEnergyType(), EnergyMath.activity(r)), BigDecimal::add);
                });
        return out;
    }

    private Map<String, Object> kpi(AnalysisDtos.Overview ov) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("energy", decimal(kpiValue(ov, 0)));
        m.put("energyYoY", yoy(ov, 0));
        m.put("emission", decimal(kpiValue(ov, 1)));
        m.put("emissionYoY", yoy(ov, 1));
        m.put("intensity", decimal(kpiValue(ov, 2)));
        m.put("intensityYoY", 0);
        m.put("quotaUsage", ov.quota() == null ? BigDecimal.ZERO : ov.quota().ratio());
        m.put("quotaUsageDelta", 0);
        return m;
    }

    private String kpiValue(AnalysisDtos.Overview ov, int i) {
        return ov.kpis() != null && ov.kpis().size() > i ? ov.kpis().get(i).value() : "0";
    }

    private BigDecimal yoy(AnalysisDtos.Overview ov, int i) {
        if (ov.kpis() == null || ov.kpis().size() <= i) return BigDecimal.ZERO;
        String foot = ov.kpis().get(i).foot();
        if (foot == null) return BigDecimal.ZERO;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)%").matcher(foot);
        return m.find() ? new BigDecimal(m.group(1)) : BigDecimal.ZERO;
    }

    private List<Map<String, Object>> monthly(AnalysisDtos.Overview ov) {
        List<Map<String, Object>> list = new ArrayList<>();
        ov.monthly().forEach(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("m", YearMonth.parse(p.period()).getMonthValue() + "月");
            m.put("period", p.period());
            m.put("energy", p.energy());
            m.put("emission", p.emission());
            list.add(m);
        });
        return list;
    }

    private List<Map<String, Object>> todos(AnalysisDtos.Overview ov) {
        List<Map<String, Object>> list = new ArrayList<>();
        int i = 1;
        for (AnalysisDtos.Todo t : ov.todos()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "t" + (i++));
            m.put("title", t.title());
            m.put("meta", t.meta());
            m.put("action", t.action());
            m.put("route", t.route());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> mix(int year) {
        AnalysisDtos.View v = analysis.energyConsumption(year + "-01", year + "-12");
        List<Map<String, Object>> list = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> row : v.table().rows()) {
            if (!row.containsKey("share") || "合计".equals(row.get("fuel"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", row.get("fuel"));
            m.put("value", row.get("share"));
            m.put("color", COLORS[i++ % COLORS.length]);
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> quota(Long org, int year, AnalysisDtos.Overview ov) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<com.carbonbal.quota.QuotaYear> qs = quotaYears.selectList(
                Wrappers.<com.carbonbal.quota.QuotaYear>lambdaQuery()
                        .eq(com.carbonbal.quota.QuotaYear::getOrgId, org)
                        .eq(com.carbonbal.quota.QuotaYear::getDisabled, 0)
                        .orderByDesc(com.carbonbal.quota.QuotaYear::getQuotaYear));
        BigDecimal usedOv = ov.quota() == null ? BigDecimal.ZERO : ov.quota().used();
        if (!qs.isEmpty()) {
            com.carbonbal.quota.QuotaYear cur = qs.stream()
                    .filter(q -> q.getQuotaYear() != null && q.getQuotaYear() == year)
                    .findFirst().orElse(qs.get(0));
            m.put("year", year);
            m.put("total", cur.getTotalQty() == null ? BigDecimal.ZERO : cur.getTotalQty());
            m.put("used", usedOv);
            m.put("next", cur.getNextYearQty() == null ? BigDecimal.ZERO : cur.getNextYearQty());
            m.put("target", new BigDecimal("6.5"));
            List<Map<String, Object>> history = new ArrayList<>();
            qs.forEach(q -> {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("year", q.getQuotaYear());
                h.put("total", q.getTotalQty());
                h.put("used", q.getQuotaYear() != null && q.getQuotaYear() == year ? usedOv : BigDecimal.ZERO);
                history.add(0, h);
            });
            m.put("history", history);
            m.put("source", "quota_year");
            return m;
        }
        String label = "碳配额";
        List<CarbonAsset> list = assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                .eq(CarbonAsset::getOrgId, org).eq(CarbonAsset::getDisabled, 0)
                .eq(CarbonAsset::getAssetYear, year));
        BigDecimal total = list.stream().filter(a -> a.getName() != null && a.getName().contains(label))
                .map(CarbonAsset::getQty).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            total = list.stream().filter(a -> "配额资产".equals(a.getType()))
                    .map(CarbonAsset::getQty).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal used = ov.quota() == null ? BigDecimal.ZERO : ov.quota().used();
        m.put("year", year);
        m.put("total", total);
        m.put("used", used);
        m.put("next", total.multiply(new BigDecimal("0.943")).setScale(0, RoundingMode.HALF_UP));
        m.put("target", new BigDecimal("6.5"));
        List<Map<String, Object>> history = new ArrayList<>();
        Map<Integer, BigDecimal> byYear = new LinkedHashMap<>();
        list.stream().filter(a -> a.getAssetYear() != null).forEach(a ->
                byYear.merge(a.getAssetYear(), a.getQty() == null ? BigDecimal.ZERO : a.getQty(), BigDecimal::add));
        byYear.forEach((y, q) -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("year", y);
            h.put("total", q);
            h.put("used", y == year ? used : BigDecimal.ZERO);
            history.add(h);
        });
        m.put("history", history);
        return m;
    }

    private String validity(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null) return "";
        return from + " ~ " + (to == null ? "长期" : to.toString());
    }

    private String size(Integer bytes) {
        if (bytes == null) return "—";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private BigDecimal decimal(String v) {
        try {
            return new BigDecimal(v);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
