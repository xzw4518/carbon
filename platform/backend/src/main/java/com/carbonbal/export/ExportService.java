package com.carbonbal.export;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.alert.AlertRecord;
import com.carbonbal.alert.AlertRecordMapper;
import com.carbonbal.asset.CarbonAsset;
import com.carbonbal.asset.CarbonAssetMapper;
import com.carbonbal.asset.CarbonTrade;
import com.carbonbal.asset.CarbonTradeMapper;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.Csv;
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
import com.carbonbal.footprint.ProductFootprint;
import com.carbonbal.footprint.ProductFootprintMapper;
import com.carbonbal.output.ProductionValue;
import com.carbonbal.output.ProductionValueMapper;
import com.carbonbal.output.TotalValue;
import com.carbonbal.output.TotalValueMapper;
import com.carbonbal.product.Product;
import com.carbonbal.product.ProductMapper;
import com.carbonbal.product.Supplier;
import com.carbonbal.product.SupplierMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ExportService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_ROWS = 50000;

    private final DeviceReadingMapper readings;
    private final DeviceMapper devices;
    private final ProductionValueMapper production;
    private final TotalValueMapper totals;
    private final EvidenceMapper evidences;
    private final ThirdPartyReportMapper thirdReports;
    private final FacilityMapper facilities;
    private final ProductMapper products;
    private final SupplierMapper suppliers;
    private final CarbonAssetMapper assets;
    private final CarbonTradeMapper trades;
    private final ProductFootprintMapper footprints;
    private final AlertRecordMapper alerts;
    private final EnergyMgmtMapper energyMgmt;
    private final RetrofitMapper retrofits;

    public ExportService(DeviceReadingMapper readings, DeviceMapper devices, ProductionValueMapper production,
                         TotalValueMapper totals, EvidenceMapper evidences, ThirdPartyReportMapper thirdReports,
                         FacilityMapper facilities, ProductMapper products, SupplierMapper suppliers,
                         CarbonAssetMapper assets, CarbonTradeMapper trades, ProductFootprintMapper footprints,
                         AlertRecordMapper alerts, EnergyMgmtMapper energyMgmt, RetrofitMapper retrofits) {
        this.readings = readings;
        this.devices = devices;
        this.production = production;
        this.totals = totals;
        this.evidences = evidences;
        this.thirdReports = thirdReports;
        this.facilities = facilities;
        this.products = products;
        this.suppliers = suppliers;
        this.assets = assets;
        this.trades = trades;
        this.footprints = footprints;
        this.alerts = alerts;
        this.energyMgmt = energyMgmt;
        this.retrofits = retrofits;
    }

    public record CsvFile(String name, byte[] bytes) {}

    public CsvFile export(String resource, String from, String to, String keyword) {
        if (resource == null || resource.isBlank()) throw BizException.validation("resource 必填");
        return switch (resource) {
            case "readings" -> readings(from, to, keyword);
            case "production-values" -> productionValues(from, to, keyword);
            case "total-values" -> totalValues(from, to, keyword);
            case "evidences" -> evidences(from, to, keyword);
            case "third-party-reports" -> thirdReports(from, to, keyword);
            case "facilities" -> facilities(keyword);
            case "products" -> products(keyword);
            case "suppliers" -> suppliers(keyword);
            case "carbon-assets" -> assets(keyword);
            case "credit-assets" -> creditAssets(keyword);
            case "retrofits" -> retrofits(keyword);
            case "energy-mgmt" -> energyMgmt(keyword);
            case "carbon-trades" -> trades(keyword);
            case "footprints" -> footprints(from, to, keyword);
            case "alert-records" -> alertRecords(keyword);
            case "devices" -> devices(keyword);
            default -> throw BizException.validation("不支持的导出资源: " + resource);
        };
    }

    private CsvFile devices(String keyword) {
        List<Device> list = devices.selectList(Wrappers.<Device>lambdaQuery()
                .eq(Device::getOrgId, CurrentUser.orgId())
                .eq(Device::getDisabled, false)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Device::getName, keyword).or().like(Device::getCode, keyword))
                .orderByAsc(Device::getCode));
        List<List<Object>> rows = list.stream().map(d -> row(d.getCode(), d.getName(), d.getEnergyType(),
                d.getUnit(), d.getDeviceType(), d.getLevel(), d.getStatus(), d.getRemark())).toList();
        return csv("设备", List.of("设备编码", "设备名称", "能源种类", "单位", "设备类型", "计量级别", "状态", "备注"), rows);
    }

    private CsvFile readings(String from, String to, String keyword) {
        LocalDateTime f = begin(from);
        LocalDateTime t = end(to);
        List<DeviceReading> list = readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                .ge(f != null, DeviceReading::getTs, f)
                .le(t != null, DeviceReading::getTs, t)
                .orderByDesc(DeviceReading::getTs)
                .last("LIMIT " + MAX_ROWS));
        Map<Long, Device> dev = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                .forEach(d -> dev.put(d.getId(), d));
        List<List<Object>> rows = list.stream()
                .filter(r -> keyword == null || keyword.isBlank()
                        || (dev.get(r.getDeviceId()) != null
                        && (dev.get(r.getDeviceId()).getName().contains(keyword)
                        || dev.get(r.getDeviceId()).getCode().contains(keyword))))
                .map(r -> {
                    Device d = dev.get(r.getDeviceId());
                    return row(r.getId(), d == null ? "" : d.getCode(), d == null ? "" : d.getName(),
                            r.getTs() == null ? "" : r.getTs().format(TS), r.getQty(), r.getUnit(),
                            r.getRate(), r.getSource(), r.getStatus());
                }).toList();
        return csv("设备数据", List.of("ID", "设备编码", "设备名称", "时间", "数值", "单位", "换算率", "来源", "状态"), rows);
    }

    private CsvFile productionValues(String from, String to, String keyword) {
        List<ProductionValue> list = production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                .eq(ProductionValue::getDisabled, 0)
                .ge(from != null && !from.isBlank(), ProductionValue::getPeriod, from)
                .le(to != null && !to.isBlank(), ProductionValue::getPeriod, to)
                .like(keyword != null && !keyword.isBlank(), ProductionValue::getProductName, keyword)
                .orderByDesc(ProductionValue::getPeriod)
                .orderByDesc(ProductionValue::getId));
        List<List<Object>> rows = list.stream().map(v -> row(v.getPeriod(), v.getProductName(),
                v.getOutputValue(), v.getUnit(), v.getQty(), v.getQtyUnit(), v.getUpdatedBy())).toList();
        return csv("产品产值", List.of("月份", "产品", "产值", "产值单位", "产量", "产量单位", "更新人"), rows);
    }

    private CsvFile totalValues(String from, String to, String keyword) {
        List<TotalValue> list = totals.selectList(Wrappers.<TotalValue>lambdaQuery()
                .eq(TotalValue::getOrgId, CurrentUser.orgId())
                .eq(TotalValue::getDisabled, 0)
                .ge(from != null && !from.isBlank(), TotalValue::getPeriod, from)
                .le(to != null && !to.isBlank(), TotalValue::getPeriod, to)
                .like(keyword != null && !keyword.isBlank(), TotalValue::getName, keyword)
                .orderByDesc(TotalValue::getPeriod)
                .orderByDesc(TotalValue::getId));
        List<List<Object>> rows = list.stream().map(v -> row(v.getPeriod(), v.getName(),
                v.getQty(), v.getQtyUnit(), v.getOutputValue(), v.getUnit(), v.getUpdatedBy())).toList();
        return csv("生产总值", List.of("月份", "名称", "产量", "产量单位", "产值", "产值单位", "更新人"), rows);
    }

    private CsvFile evidences(String from, String to, String keyword) {
        LocalDate f = date(from);
        LocalDate t = date(to);
        List<Evidence> list = evidences.selectList(Wrappers.<Evidence>lambdaQuery()
                .eq(Evidence::getOrgId, CurrentUser.orgId())
                .eq(Evidence::getDisabled, 0)
                .ge(f != null, Evidence::getAtDate, f)
                .le(t != null, Evidence::getAtDate, t)
                .like(keyword != null && !keyword.isBlank(), Evidence::getName, keyword)
                .orderByDesc(Evidence::getAtDate));
        List<List<Object>> rows = list.stream().map(e -> row(e.getName(), e.getCode(), e.getDescription(),
                e.getValidFrom(), e.getValidTo(), e.getAtDate())).toList();
        return csv("原始凭证", List.of("名称", "编号", "说明", "有效期起", "有效期止", "凭证日期"), rows);
    }

    private CsvFile thirdReports(String from, String to, String keyword) {
        LocalDate f = date(from);
        LocalDate t = date(to);
        List<ThirdPartyReport> list = thirdReports.selectList(Wrappers.<ThirdPartyReport>lambdaQuery()
                .eq(ThirdPartyReport::getOrgId, CurrentUser.orgId())
                .eq(ThirdPartyReport::getDisabled, 0)
                .ge(f != null, ThirdPartyReport::getAtDate, f)
                .le(t != null, ThirdPartyReport::getAtDate, t)
                .like(keyword != null && !keyword.isBlank(), ThirdPartyReport::getName, keyword)
                .orderByDesc(ThirdPartyReport::getAtDate));
        List<List<Object>> rows = list.stream().map(r -> row(r.getName(), r.getDescription(),
                r.getValidFrom(), r.getValidTo(), r.getAtDate(), r.getFileId())).toList();
        return csv("第三方报告", List.of("名称", "说明", "有效期起", "有效期止", "报告日期", "文件ID"), rows);
    }

    private CsvFile facilities(String keyword) {
        List<Facility> list = facilities.selectList(Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, CurrentUser.orgId())
                .eq(Facility::getDisabled, 0)
                .orderByAsc(Facility::getTreeLevel)
                .orderByAsc(Facility::getSortNo));
        Map<Long, String> names = new HashMap<>();
        list.forEach(x -> names.put(x.getId(), x.getName()));
        List<List<Object>> rows = list.stream()
                .filter(x -> keyword == null || keyword.isBlank()
                        || x.getName().contains(keyword) || x.getCode().contains(keyword))
                .map(x -> row(x.getName(), x.getCode(), names.getOrDefault(x.getParentId(), ""),
                        x.getTreeLevel(), x.getSortNo(), x.getRemark())).toList();
        return csv("设施", List.of("设施名称", "设施编码", "上级设施", "层级", "排序", "备注"), rows);
    }

    private CsvFile products(String keyword) {
        List<Product> list = products.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getOrgId, CurrentUser.orgId())
                .eq(Product::getDisabled, 0)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Product::getName, keyword).or().like(Product::getCode, keyword))
                .orderByAsc(Product::getCode));
        List<List<Object>> rows = list.stream().map(p -> row(p.getName(), p.getCode(), p.getModel(),
                p.getType(), p.getCls(), p.getUnit(), p.getL1(), p.getL2(), p.getL3())).toList();
        return csv("产品", List.of("产品名称", "编码", "规格型号", "类型", "分类", "单位",
                "1级能耗", "2级能耗", "3级能耗"), rows);
    }

    private CsvFile suppliers(String keyword) {
        List<Supplier> list = suppliers.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0)
                .like(keyword != null && !keyword.isBlank(), Supplier::getName, keyword)
                .orderByAsc(Supplier::getSort));
        Map<Long, String> nameById = new HashMap<>();
        for (Supplier s : suppliers.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0))) {
            nameById.put(s.getId(), s.getName());
        }
        List<List<Object>> rows = list.stream().map(s -> row(s.getName(),
                s.getParentId() == null ? "" : nameById.getOrDefault(s.getParentId(), ""),
                s.getProduct(), s.getUnit(), s.getFootprint(), s.getFpUnit(), s.getDate(),
                s.getTarget(), s.getLevel())).toList();
        return csv("供应商", List.of("供应商", "上级供应商", "供应产品", "数量单位", "碳足迹", "足迹单位",
                "日期", "核算范围", "层级"), rows);
    }

    private CsvFile assets(String keyword) {
        List<CarbonAsset> list = assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                .eq(CarbonAsset::getOrgId, CurrentUser.orgId())
                .eq(CarbonAsset::getDisabled, 0)
                .like(keyword != null && !keyword.isBlank(), CarbonAsset::getName, keyword)
                .orderByDesc(CarbonAsset::getAssetYear));
        List<List<Object>> rows = list.stream().map(a -> row(a.getName(), a.getType(), a.getQty(),
                a.getUnit(), a.getAssetYear(), a.getStatus())).toList();
        return csv("碳资产", List.of("资产名称", "类型", "数量", "单位", "年度", "状态"), rows);
    }

    /** 信用资产：复用碳资产表，按类型过滤。 */
    private CsvFile creditAssets(String keyword) {
        List<CarbonAsset> list = assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                .eq(CarbonAsset::getOrgId, CurrentUser.orgId())
                .eq(CarbonAsset::getDisabled, 0)
                .eq(CarbonAsset::getType, "信用资产")
                .like(keyword != null && !keyword.isBlank(), CarbonAsset::getName, keyword)
                .orderByDesc(CarbonAsset::getAssetYear));
        List<List<Object>> rows = list.stream().map(a -> row(a.getName(), a.getType(), a.getQty(),
                a.getUnit(), a.getAssetYear(), a.getStatus())).toList();
        return csv("信用资产", List.of("资产名称", "类型", "数量", "单位", "年度", "状态"), rows);
    }

    private CsvFile retrofits(String keyword) {
        List<Retrofit> list = retrofits.selectList(Wrappers.<Retrofit>lambdaQuery()
                .eq(Retrofit::getOrgId, CurrentUser.orgId())
                .eq(Retrofit::getDisabled, 0)
                .like(keyword != null && !keyword.isBlank(), Retrofit::getName, keyword)
                .orderByDesc(Retrofit::getProjectYear));
        List<List<Object>> rows = list.stream().map(r -> row(r.getProjectYear(), r.getName(), r.getInvest(),
                r.getInvestUnit(), r.getSaving(), r.getSavingUnit(), r.getContract(), r.getDone(),
                r.getAtDate())).toList();
        return csv("节能改造", List.of("年度", "项目名称", "投资额", "投资单位", "节能量", "节能单位",
                "合同", "完成情况", "日期"), rows);
    }

    private CsvFile energyMgmt(String keyword) {
        List<EnergyMgmt> list = energyMgmt.selectList(Wrappers.<EnergyMgmt>lambdaQuery()
                .eq(EnergyMgmt::getOrgId, CurrentUser.orgId())
                .eq(EnergyMgmt::getDisabled, 0)
                .like(keyword != null && !keyword.isBlank(), EnergyMgmt::getOrgName, keyword)
                .orderByDesc(EnergyMgmt::getAtDate));
        List<List<Object>> rows = list.stream().map(m -> row(m.getAtDate(), m.getOrgName(), m.getDuty(),
                m.getTeam(), m.getPost(), m.getCharge(), m.getFiller(), m.getContact(), m.getSystemName(),
                m.getReport(), m.getFiling(), m.getInstName(), m.getStat(), m.getPromo(),
                m.getTraining(), m.getSubsidy())).toList();
        return csv("能源管理", List.of("填报日期", "机构名称", "负责人", "团队", "岗位", "分管领导",
                "填报人", "联系方式", "体系名称", "报告", "备案", "制度", "统计", "宣传", "培训", "补贴"), rows);
    }

    private CsvFile trades(String keyword) {
        List<CarbonTrade> list = trades.selectList(Wrappers.<CarbonTrade>lambdaQuery()
                .eq(CarbonTrade::getOrgId, CurrentUser.orgId())
                .eq(CarbonTrade::getDisabled, 0)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(CarbonTrade::getAssetName, keyword).or().like(CarbonTrade::getParty, keyword))
                .orderByDesc(CarbonTrade::getTradeDate));
        List<List<Object>> rows = list.stream().map(t -> row(t.getAssetName(), t.getType(), t.getQty(),
                t.getPrice(), t.getAmount(), t.getParty(), t.getUsePurpose(), t.getTradeDate(), t.getStatus())).toList();
        return csv("交易记录", List.of("资产", "类型", "数量", "单价", "金额", "对手方", "用途", "交易日期", "状态"), rows);
    }

    private CsvFile footprints(String from, String to, String keyword) {
        LocalDate f = date(from);
        LocalDate t = date(to);
        List<ProductFootprint> list = footprints.selectList(Wrappers.<ProductFootprint>lambdaQuery()
                .eq(ProductFootprint::getOrgId, CurrentUser.orgId())
                .eq(ProductFootprint::getDisabled, 0)
                .ge(f != null, ProductFootprint::getDate, f)
                .le(t != null, ProductFootprint::getDate, t)
                .like(keyword != null && !keyword.isBlank(), ProductFootprint::getName, keyword)
                .orderByDesc(ProductFootprint::getDate));
        List<List<Object>> rows = list.stream().map(p -> row(p.getName(), p.getUnit(), p.getQty(),
                p.getFootprint(), p.getDate(), p.getBoundary(), p.getStageRaw(), p.getStageTransport(),
                p.getStageMake())).toList();
        return csv("碳足迹", List.of("产品", "单位", "数量", "碳足迹", "日期", "边界",
                "原材料", "运输", "生产"), rows);
    }

    private CsvFile alertRecords(String keyword) {
        List<AlertRecord> list = alerts.selectList(Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                .eq(AlertRecord::getDisabled, 0)
                .like(keyword != null && !keyword.isBlank(), AlertRecord::getTitle, keyword)
                .orderByDesc(AlertRecord::getOccurredAt)
                .last("LIMIT " + MAX_ROWS));
        List<List<Object>> rows = list.stream().map(a -> row(a.getTitle(), a.getLevel(), a.getMetric(),
                a.getMetricValue(), a.getThreshold(), a.getUnit(), a.getDeviceCode(),
                a.getOccurredAt() == null ? "" : a.getOccurredAt().format(TS),
                a.getStatus(), Integer.valueOf(1).equals(a.getIsRead()) ? "已读" : "未读")).toList();
        return csv("预警记录", List.of("标题", "级别", "指标", "当前值", "阈值", "单位", "设备",
                "发生时间", "状态", "已读"), rows);
    }

    private CsvFile csv(String label, List<String> headers, List<List<Object>> rows) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return new CsvFile(label + "_" + stamp + ".csv", Csv.build(headers, rows));
    }

    private List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private LocalDateTime begin(String v) {
        LocalDate d = date(v);
        return d == null ? null : d.atStartOfDay();
    }

    private LocalDateTime end(String v) {
        LocalDate d = date(v);
        return d == null ? null : d.atTime(23, 59, 59);
    }

    private LocalDate date(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return v.length() == 7 ? LocalDate.parse(v + "-01") : LocalDate.parse(v);
        } catch (Exception e) {
            throw BizException.validation("日期格式为 YYYY-MM-DD 或 YYYY-MM");
        }
    }
}
