package com.carbonbal.analysis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.accounting.AccountingPeriod;
import com.carbonbal.accounting.AccountingPeriodMapper;
import com.carbonbal.alert.AlertRecord;
import com.carbonbal.alert.AlertRecordMapper;
import com.carbonbal.asset.CarbonAsset;
import com.carbonbal.asset.CarbonAssetMapper;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.EnergyMath;
import com.carbonbal.device.Device;
import com.carbonbal.device.DeviceMapper;
import com.carbonbal.device.DeviceReading;
import com.carbonbal.device.DeviceReadingMapper;
import com.carbonbal.energy.Retrofit;
import com.carbonbal.energy.RetrofitMapper;
import com.carbonbal.factor.EmissionFactor;
import com.carbonbal.factor.FactorService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 能碳分析聚合：能耗/碳排/配额/预警 4 大类 8 个分析页面的数据组装。
 *
 * <p><b>技术债（P2-5，v1.1 拆分边界）</b>：本类已超过 1000 行，8 个分析口径共用一个类，
 * 任一页面改口径都要重新理解整类。已登记拆分方案：
 * {@code EnergyAnalysisService}（能耗/能流/单耗）、{@code CarbonAnalysisService}（碳排/强度/趋势）、
 * {@code QuotaAnalysisService}（配额/履约/预警联动），公共的取数工具抽到
 * {@code AnalysisSupport}（当前类中的 private helper）。
 * <b>约束：本轮及之后的新代码不得继续堆入本类</b>。
 */
@Service
public class AnalysisService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Set<String> SCOPE2_FUELS = Set.of("外购电力", "外购蒸汽");

    private final DeviceMapper devices;
    private final DeviceReadingMapper readings;
    private final FactorService factors;
    private final AccountingPeriodMapper periods;
    private final ProductionValueMapper production;
    private final TotalValueMapper totals;
    private final CarbonAssetMapper assets;
    private final ProductMapper products;
    private final AlertRecordMapper alerts;
    private final RetrofitMapper retrofits;
    private final ReportMapper reports;
    private final OrgMapper orgs;
    private final ProductFootprintMapper footprints;
    private final SupplierMapper suppliers;

    public AnalysisService(DeviceMapper devices, DeviceReadingMapper readings, FactorService factors,
                           AccountingPeriodMapper periods, ProductionValueMapper production,
                           TotalValueMapper totals, CarbonAssetMapper assets, ProductMapper products,
                           AlertRecordMapper alerts, RetrofitMapper retrofits, ReportMapper reports,
                           OrgMapper orgs, ProductFootprintMapper footprints, SupplierMapper suppliers) {
        this.devices = devices;
        this.readings = readings;
        this.factors = factors;
        this.periods = periods;
        this.production = production;
        this.totals = totals;
        this.assets = assets;
        this.products = products;
        this.alerts = alerts;
        this.retrofits = retrofits;
        this.reports = reports;
        this.orgs = orgs;
        this.footprints = footprints;
        this.suppliers = suppliers;
    }

    /* ==================== 1. 能耗查询 ==================== */

    public AnalysisDtos.View energyConsumption(String from, String to) {
        Range range = range(from, to);
        Stats s = stats(range);
        BigDecimal total = s.totalTce;

        List<AnalysisDtos.Series> series = new ArrayList<>();
        series.add(new AnalysisDtos.Series("综合能耗", "bar", "tce",
                s.labels(), range.months().stream().map(s::monthTce).toList()));
        s.byFuel.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1]))
                .limit(4)
                .forEach(e -> series.add(new AnalysisDtos.Series(e.getKey(), "line", "tce",
                        s.labels(), range.months().stream().map(m -> s.monthFuel(m, e.getKey())[1]).toList())));

        List<Map<String, Object>> rows = new ArrayList<>();
        s.byFuel.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1]))
                .forEach(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("fuel", e.getKey());
                    r.put("activity", scale(e.getValue()[0], 2));
                    r.put("unit", EnergyMath.unit(e.getKey()));
                    r.put("tce", scale(e.getValue()[1], 2));
                    r.put("share", EnergyMath.ratio(e.getValue()[1], total));
                    rows.add(r);
                });
        rows.add(totalRow("合计", total));

        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("综合能耗", fmt(total, 2), "tce", range.label()),
                new AnalysisDtos.Kpi("电力", fmt(s.fuelTce("外购电力"), 2), "tce",
                        "占比 " + EnergyMath.ratio(s.fuelTce("外购电力"), total) + "%"),
                new AnalysisDtos.Kpi("天然气", fmt(s.fuelTce("天然气"), 2), "tce",
                        "占比 " + EnergyMath.ratio(s.fuelTce("天然气"), total) + "%"),
                new AnalysisDtos.Kpi("统计月份", String.valueOf(range.months().size()), "个月", range.label())
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("能耗明细", List.of(
                new AnalysisDtos.Column("fuel", "能源品种", "text"),
                new AnalysisDtos.Column("activity", "活动数据", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("tce", "折标煤(tce)", "number"),
                new AnalysisDtos.Column("share", "占比(%)", "number")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /* ==================== 2. 碳排放查询 ==================== */

    public AnalysisDtos.View carbonEmission(String from, String to) {
        Range range = range(from, to);
        Stats s = stats(range);
        Map<YearMonth, BigDecimal> output = outputByMonth(range);
        BigDecimal totalOutput = output.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal intensity = totalOutput.signum() == 0 ? BigDecimal.ZERO
                : s.totalEmission.divide(totalOutput, 4, RoundingMode.HALF_UP);

        List<AnalysisDtos.Series> series = List.of(
                new AnalysisDtos.Series("碳排放量", "line", "tCO2e", s.labels(),
                        range.months().stream().map(s::monthEmission).toList()),
                new AnalysisDtos.Series("产值", "line", "万元", s.labels(),
                        range.months().stream().map(m -> output.getOrDefault(m, BigDecimal.ZERO)).toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        s.byFuel.entrySet().stream()
                .sorted((a, b) -> b.getValue()[2].compareTo(a.getValue()[2]))
                .forEach(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("fuel", e.getKey());
                    r.put("activity", scale(e.getValue()[0], 2));
                    r.put("unit", EnergyMath.unit(e.getKey()));
                    r.put("tce", scale(e.getValue()[1], 2));
                    r.put("emission", scale(e.getValue()[2], 2));
                    r.put("share", EnergyMath.ratio(e.getValue()[2], s.totalEmission));
                    rows.add(r);
                });
        Map<String, Object> totalRow = new LinkedHashMap<>();
        totalRow.put("fuel", "合计");
        totalRow.put("activity", "");
        totalRow.put("unit", "");
        totalRow.put("tce", scale(s.totalTce, 2));
        totalRow.put("emission", scale(s.totalEmission, 2));
        totalRow.put("share", HUNDRED.setScale(2));
        rows.add(totalRow);

        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("碳排放总量", fmt(s.totalEmission, 2), "tCO2e", range.label()),
                new AnalysisDtos.Kpi("范围一", fmt(s.scope1, 2), "tCO2e", "直接排放"),
                new AnalysisDtos.Kpi("范围二", fmt(s.scope2, 2), "tCO2e", "外购能源间接排放"),
                new AnalysisDtos.Kpi("单位产值碳排放", fmt(intensity, 4), "tCO2e/万元", "产值合计 " + fmt(totalOutput, 2) + " 万元")
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("排放明细", List.of(
                new AnalysisDtos.Column("fuel", "能源品种", "text"),
                new AnalysisDtos.Column("activity", "活动数据", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("tce", "折标煤(tce)", "number"),
                new AnalysisDtos.Column("emission", "排放量(tCO2e)", "number"),
                new AnalysisDtos.Column("share", "占比(%)", "number")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /* ==================== 3. 能效对标 ==================== */

    public AnalysisDtos.View energyEfficiency(String from, String to) {
        Range range = range(from, to);
        Stats s = stats(range);
        Map<YearMonth, BigDecimal> output = outputByMonth(range);
        BigDecimal totalOutput = output.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgIntensity = totalOutput.signum() == 0 ? BigDecimal.ZERO
                : s.totalTce.divide(totalOutput, 4, RoundingMode.HALF_UP);

        Map<Long, Product> productMap = new HashMap<>();
        products.selectList(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getOrgId, CurrentUser.orgId())
                        .eq(Product::getDisabled, 0))
                .forEach(p -> productMap.put(p.getId(), p));

        List<ProductionValue> pvs = production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                .eq(ProductionValue::getDisabled, 0)
                .ge(ProductionValue::getPeriod, range.from.toString())
                .le(ProductionValue::getPeriod, range.to.toString()));
        Map<YearMonth, List<ProductionValue>> pvByMonth = new HashMap<>();
        for (ProductionValue pv : pvs) {
            pvByMonth.computeIfAbsent(YearMonth.parse(pv.getPeriod()), k -> new ArrayList<>()).add(pv);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal[]> prodAgg = new LinkedHashMap<>(); // name -> [qty, energy, minL1, maxL2, maxL3]
        for (YearMonth m : range.months()) {
            List<ProductionValue> list = pvByMonth.getOrDefault(m, List.of());
            BigDecimal monthValue = list.stream().map(ProductionValue::getOutputValue)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal monthEnergy = s.monthTce(m);
            for (ProductionValue pv : list) {
                BigDecimal pvValue = pv.getOutputValue() == null ? BigDecimal.ZERO : pv.getOutputValue();
                BigDecimal pvQty = pv.getQty() == null ? BigDecimal.ZERO : pv.getQty();
                BigDecimal share = monthValue.signum() == 0 ? BigDecimal.ZERO
                        : pvValue.divide(monthValue, 6, RoundingMode.HALF_UP);
                BigDecimal allocEnergy = monthEnergy.multiply(share).setScale(4, RoundingMode.HALF_UP);
                BigDecimal intensity = pvQty.signum() == 0
                        ? BigDecimal.ZERO : allocEnergy.divide(pvQty, 4, RoundingMode.HALF_UP);
                Product product = productMap.get(pv.getProductId());
                BigDecimal l1 = product == null ? null : product.getL1();
                BigDecimal l2 = product == null ? null : product.getL2();
                BigDecimal l3 = product == null ? null : product.getL3();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("period", m.toString());
                r.put("product", pv.getProductName());
                r.put("qty", scale(pv.getQty(), 2));
                r.put("energy", scale(allocEnergy, 2));
                r.put("l1", l1 == null ? "" : scale(l1, 2));
                r.put("l2", l2 == null ? "" : scale(l2, 2));
                r.put("l3", l3 == null ? "" : scale(l3, 2));
                r.put("intensity", scale(intensity, 4));
                r.put("level", level(intensity, l1, l2, l3));
                rows.add(r);

                BigDecimal[] agg = prodAgg.computeIfAbsent(pv.getProductName(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                agg[0] = agg[0].add(pv.getQty() == null ? BigDecimal.ZERO : pv.getQty());
                agg[1] = agg[1].add(allocEnergy);
            }
        }

        List<AnalysisDtos.Series> series = List.of(
                new AnalysisDtos.Series("单位产值能耗", "line", "tce/万元", s.labels(),
                        range.months().stream().map(m -> {
                            BigDecimal v = output.getOrDefault(m, BigDecimal.ZERO);
                            return v.signum() == 0 ? BigDecimal.ZERO
                                    : s.monthTce(m).divide(v, 4, RoundingMode.HALF_UP);
                        }).toList()),
                new AnalysisDtos.Series("产品单位能耗", "bar", "tce/单位", prodAgg.keySet().stream().toList(),
                        prodAgg.values().stream().map(a -> a[0].signum() == 0 ? BigDecimal.ZERO
                                : a[1].divide(a[0], 4, RoundingMode.HALF_UP)).toList()));

        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("总能源量", fmt(s.totalTce, 2), "tce", range.label()),
                new AnalysisDtos.Kpi("平均单位产值能耗", fmt(avgIntensity, 4), "tce/万元", "产值合计 " + fmt(totalOutput, 2) + " 万元"),
                new AnalysisDtos.Kpi("对标产品", String.valueOf(prodAgg.size()), "个", "按产值分摊能耗"),
                new AnalysisDtos.Kpi("统计月份", String.valueOf(range.months().size()), "个月", range.label())
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("能效对标明细", List.of(
                new AnalysisDtos.Column("period", "月份", "text"),
                new AnalysisDtos.Column("product", "产品", "text"),
                new AnalysisDtos.Column("qty", "产量", "number"),
                new AnalysisDtos.Column("energy", "分摊能耗(tce)", "number"),
                new AnalysisDtos.Column("l1", "1级", "number"),
                new AnalysisDtos.Column("l2", "2级", "number"),
                new AnalysisDtos.Column("l3", "3级", "number"),
                new AnalysisDtos.Column("intensity", "实际单耗", "number"),
                new AnalysisDtos.Column("level", "能效等级", "tag")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /* ==================== 4. 能流分析 ==================== */

    public AnalysisDtos.View energyFlow(String from, String to) {
        Range range = range(from, to);
        Stats s = stats(range);

        List<AnalysisDtos.SankeyNode> level1 = new ArrayList<>();
        Map<String, List<AnalysisDtos.SankeyNode>> level2 = new LinkedHashMap<>();
        List<AnalysisDtos.SankeyLink> links = new ArrayList<>();
        Map<String, BigDecimal> fuelTce = new LinkedHashMap<>();
        Map<String, Set<Long>> fuelDevices = new HashMap<>();

        s.readings.forEach(r -> {
            Device d = s.devices.get(r.getDeviceId());
            if (d == null || !EnergyMath.supported(d.getEnergyType())) return;
            BigDecimal tce = EnergyMath.tce(d.getEnergyType(), EnergyMath.activity(r));
            fuelTce.merge(d.getEnergyType(), tce, BigDecimal::add);
            fuelDevices.computeIfAbsent(d.getEnergyType(), k -> new HashSet<>()).add(d.getId());
        });
        s.byFuel.forEach((fuel, v) -> {
            level1.add(new AnalysisDtos.SankeyNode("fuel-" + fuel, fuel, scale(v[1], 2)));
            links.add(new AnalysisDtos.SankeyLink("total", "fuel-" + fuel, scale(v[1], 2)));
        });
        // 按设备聚合第三层
        Map<String, Map<Long, BigDecimal>> devAgg = new LinkedHashMap<>();
        s.readings.forEach(r -> {
            Device d = s.devices.get(r.getDeviceId());
            if (d == null || !EnergyMath.supported(d.getEnergyType())) return;
            devAgg.computeIfAbsent(d.getEnergyType(), k -> new LinkedHashMap<>())
                    .merge(d.getId(), EnergyMath.tce(d.getEnergyType(), EnergyMath.activity(r)), BigDecimal::add);
        });
        devAgg.forEach((fuel, devs) -> devs.forEach((devId, tce) -> {
            Device d = s.devices.get(devId);
            String nodeId = "dev-" + devId;
            level2.computeIfAbsent(fuel, k -> new ArrayList<>())
                    .add(new AnalysisDtos.SankeyNode(nodeId, d == null ? String.valueOf(devId) : d.getName(),
                            scale(tce, 2)));
            links.add(new AnalysisDtos.SankeyLink("fuel-" + fuel, nodeId, scale(tce, 2)));
        }));

        List<List<AnalysisDtos.SankeyNode>> layers = new ArrayList<>();
        layers.add(List.of(new AnalysisDtos.SankeyNode("total", "总能耗", scale(s.totalTce, 2))));
        layers.add(level1);
        layers.add(level2.values().stream().flatMap(List::stream).toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        s.byFuel.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1]))
                .forEach(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("fuel", e.getKey());
                    r.put("tce", scale(e.getValue()[1], 2));
                    r.put("share", EnergyMath.ratio(e.getValue()[1], s.totalTce));
                    r.put("devices", fuelDevices.getOrDefault(e.getKey(), Set.of()).size());
                    rows.add(r);
                });

        String topFuel = fuelTce.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("—");
        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("总能耗", fmt(s.totalTce, 2), "tce", range.label()),
                new AnalysisDtos.Kpi("能源品种", String.valueOf(s.byFuel.size()), "种", "参与折标"),
                new AnalysisDtos.Kpi("最大流向", topFuel, "", "占比 " + EnergyMath.ratio(s.fuelTce(topFuel), s.totalTce) + "%"),
                new AnalysisDtos.Kpi("计量设备", String.valueOf(fuelDevices.values().stream()
                        .flatMap(Set::stream).distinct().count()), "台", "含多品种设备")
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("能流明细", List.of(
                new AnalysisDtos.Column("fuel", "能源品种", "text"),
                new AnalysisDtos.Column("tce", "折标煤(tce)", "number"),
                new AnalysisDtos.Column("share", "占比(%)", "number"),
                new AnalysisDtos.Column("devices", "设备数", "number")), rows);
        AnalysisDtos.Sankey sankey = new AnalysisDtos.Sankey(layers, links);
        return new AnalysisDtos.View(kpis, List.of(), table, sankey);
    }

    /* ==================== 5. 策略推荐 ==================== */

    public AnalysisDtos.View strategy(String from, String to) {
        Range range = range(from, to);
        Stats s = stats(range);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> high = new ArrayList<>();

        BigDecimal powerShare = EnergyMath.ratio(s.fuelTce("外购电力"), s.totalTce);
        if (powerShare.compareTo(new BigDecimal("60")) > 0) {
            addRec(rows, high, "峰谷分时用电优化",
                    "电力占比 " + powerShare + "%，负荷集中", "高", "预计降低电费 2%~3%");
        }
        // 环比：区间最后两个月
        List<YearMonth> months = range.months();
        if (months.size() >= 2) {
            YearMonth last = months.get(months.size() - 1);
            YearMonth prev = months.get(months.size() - 2);
            s.byFuel.keySet().forEach(fuel -> {
                BigDecimal a = s.monthFuel(prev, fuel)[1];
                BigDecimal b = s.monthFuel(last, fuel)[1];
                if (a.signum() > 0) {
                    BigDecimal change = b.subtract(a).multiply(HUNDRED).divide(a, 2, RoundingMode.HALF_UP);
                    if (change.compareTo(new BigDecimal("10")) > 0 && b.subtract(a).compareTo(new BigDecimal("0.5")) > 0) {
                        addRec(rows, high, fuel + "消耗环比上升",
                                a + " → " + b + " tce（+" + change + "%）", "高", "核查泄漏与设备效率，预计回落 3%~5%");
                    }
                }
            });
        }
        BigDecimal yearOutput = outputValue(String.valueOf(range.to.getYear()));
        BigDecimal intensity = yearOutput.signum() == 0 ? BigDecimal.ZERO
                : s.totalTce.divide(yearOutput, 4, RoundingMode.HALF_UP);
        if (intensity.compareTo(new BigDecimal("0.05")) > 0) {
            addRec(rows, high, "单位产值能耗偏高",
                    "当前 " + intensity + " tce/万元，高于 0.05 参考线", "中", "对标 1 级能效，预计节能 4%~6%");
        }
        long offline = s.devices.values().stream().filter(d -> "offline".equals(d.getStatus())).count();
        if (offline > 0) {
            addRec(rows, high, "离线设备排查",
                    offline + " 台设备处于离线状态，计量数据可能缺失", "中", "恢复计量，避免核算缺口");
        }
        List<Retrofit> undone = retrofits.selectList(Wrappers.<Retrofit>lambdaQuery()
                .eq(Retrofit::getOrgId, CurrentUser.orgId())
                .eq(Retrofit::getDisabled, 0)
                .eq(Retrofit::getDone, "否"));
        if (!undone.isEmpty()) {
            addRec(rows, high, "节能改造项目推进",
                    undone.size() + " 个项目未完成：" + String.join("、",
                            undone.stream().map(Retrofit::getName).limit(3).toList()), "低", "按期完成后可形成实际节能量");
        }
        BigDecimal quota = quotaTotal(range.to.getYear());
        Stats yearStats = stats(new Range(YearMonth.of(range.to.getYear(), 1), YearMonth.of(range.to.getYear(), 12)));
        BigDecimal usage = EnergyMath.ratio(yearStats.totalEmission, quota);
        if (quota.signum() > 0 && usage.compareTo(new BigDecimal("80")) > 0) {
            addRec(rows, high, "配额履约预警",
                    "年度配额使用率 " + usage + "%，剩余 " + fmt(quota.subtract(yearStats.totalEmission), 2) + " tCO2e",
                    "高", "提前安排配额购入或减排计划");
        }
        if (rows.isEmpty()) {
            addRec(rows, high, "维持当前用能结构",
                    "未触发阈值规则，各项指标处于合理区间", "低", "保持月度监测");
        }

        List<AnalysisDtos.Series> series = List.of(new AnalysisDtos.Series("能源消费结构", "bar", "tce",
                s.byFuel.keySet().stream().toList(),
                s.byFuel.values().stream().map(v -> scale(v[1], 2)).toList()));
        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("建议条数", String.valueOf(rows.size()), "条", range.label()),
                new AnalysisDtos.Kpi("高优先级", String.valueOf(high.size()), "条", "需本月处理"),
                new AnalysisDtos.Kpi("覆盖品种", String.valueOf(s.byFuel.size()), "种", "参与折标"),
                new AnalysisDtos.Kpi("配额使用率", quota.signum() == 0 ? "—" : usage.toPlainString(), "%",
                        "年度累计排放")
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("策略建议", List.of(
                new AnalysisDtos.Column("title", "建议", "text"),
                new AnalysisDtos.Column("basis", "依据", "text"),
                new AnalysisDtos.Column("priority", "优先级", "tag"),
                new AnalysisDtos.Column("effect", "预计效果", "text")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /* ==================== 6. 碳配额核算 ==================== */

    public AnalysisDtos.View quota(Integer year) {
        int y = year == null ? YearMonth.now().getYear() : year;
        Stats s = stats(new Range(YearMonth.of(y, 1), YearMonth.of(y, 12)));
        BigDecimal quota = quotaTotal(y);
        BigDecimal usage = EnergyMath.ratio(s.totalEmission, quota);
        BigDecimal remaining = quota.subtract(s.totalEmission);
        BigDecimal next = quota.multiply(new BigDecimal("0.943")).setScale(0, RoundingMode.HALF_UP);

        int lastMonth = y == YearMonth.now().getYear() ? YearMonth.now().getMonthValue() : 12;
        BigDecimal monthlyQuota = quota.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
        List<String> labels = new ArrayList<>();
        List<BigDecimal> emissionSeries = new ArrayList<>();
        List<BigDecimal> quotaSeries = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int m = 1; m <= lastMonth; m++) {
            YearMonth ym = YearMonth.of(y, m);
            BigDecimal e = s.monthEmission(ym);
            labels.add(ym.toString());
            emissionSeries.add(scale(e, 2));
            quotaSeries.add(monthlyQuota);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", ym.toString());
            r.put("emission", scale(e, 2));
            r.put("quota", monthlyQuota);
            r.put("gap", scale(monthlyQuota.subtract(e), 2));
            rows.add(r);
        }

        List<AnalysisDtos.Series> series = List.of(
                new AnalysisDtos.Series("月度排放", "bar", "tCO2e", labels, emissionSeries),
                new AnalysisDtos.Series("月度配额(均摊)", "line", "tCO2e", labels, quotaSeries));
        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("碳排放配额", fmt(quota, 2), "tCO2e", y + " 年度"),
                new AnalysisDtos.Kpi("已排放", fmt(s.totalEmission, 2), "tCO2e", "截至 " + lastMonth + " 月"),
                new AnalysisDtos.Kpi("配额使用率", quota.signum() == 0 ? "—" : usage.toPlainString(), "%",
                        "剩余 " + fmt(remaining, 2)),
                new AnalysisDtos.Kpi("预计下年度配额", fmt(next, 0), "tCO2e", "按基准年下降 5.7%")
        );
        AnalysisDtos.Table table = new AnalysisDtos.Table("配额与排放", List.of(
                new AnalysisDtos.Column("period", "月份", "text"),
                new AnalysisDtos.Column("emission", "排放量(tCO2e)", "number"),
                new AnalysisDtos.Column("quota", "月度配额(tCO2e)", "number"),
                new AnalysisDtos.Column("gap", "余量(tCO2e)", "number")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /* ==================== 7. 碳足迹分析 ==================== */

    public AnalysisDtos.View footprintAnalysis(Long productId, String product, Integer year) {
        List<ProductFootprint> list = footprints.selectList(Wrappers.<ProductFootprint>lambdaQuery()
                .eq(ProductFootprint::getOrgId, CurrentUser.orgId())
                .eq(ProductFootprint::getDisabled, 0)
                .eq(productId != null, ProductFootprint::getProductId, productId)
                .eq(product != null && !product.isBlank(), ProductFootprint::getName, product)
                .ge(year != null, ProductFootprint::getDate, year == null ? null : LocalDate.of(year, 1, 1))
                .le(year != null, ProductFootprint::getDate, year == null ? null : LocalDate.of(year, 12, 31))
                .orderByDesc(ProductFootprint::getDate)
                .orderByDesc(ProductFootprint::getId));
        if (list.isEmpty()) {
            boolean filtered = productId != null || (product != null && !product.isBlank()) || year != null;
            if (filtered) return emptyFootprintView();
            throw BizException.notFound("暂无产品足迹数据");
        }

        ProductFootprint latest = list.get(0);
        BigDecimal raw = nz(latest.getStageRaw());
        BigDecimal transport = nz(latest.getStageTransport());
        BigDecimal make = nz(latest.getStageMake());
        BigDecimal stageSum = raw.add(transport).add(make);

        boolean allProducts = productId == null && (product == null || product.isBlank());
        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("产品", allProducts ? "全部" : latest.getName(), "",
                        allProducts ? "全部产品 · " + list.size() + " 条记录" : "最近核算 " + latest.getDate()),
                new AnalysisDtos.Kpi("碳足迹值", fmt(latest.getFootprint(), 4),
                        "tCO2e/" + latest.getUnit(), latest.getBoundary()),
                new AnalysisDtos.Kpi("阶段合计", fmt(stageSum, 4), "tCO2e/" + latest.getUnit(),
                        "原料 + 运输 + 制造"),
                new AnalysisDtos.Kpi("核算记录", String.valueOf(list.size()), "条",
                        allProducts ? "全部产品" : "当前产品")
        );
        List<AnalysisDtos.Series> series = List.of(new AnalysisDtos.Series(
                "阶段构成", "bar", "tCO2e/" + latest.getUnit(),
                List.of("原材料获取", "原材料运输", "生产制造"),
                List.of(scale(raw, 4), scale(transport, 4), scale(make, 4))));
        List<Map<String, Object>> rows = list.stream().map(f -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", f.getDate() == null ? "" : f.getDate().toString());
            r.put("product", f.getName());
            r.put("qty", scale(f.getQty(), 2));
            r.put("footprint", scale(f.getFootprint(), 4));
            r.put("unit", "tCO2e/" + f.getUnit());
            r.put("boundary", f.getBoundary());
            return r;
        }).toList();
        AnalysisDtos.Table table = new AnalysisDtos.Table("足迹记录", List.of(
                new AnalysisDtos.Column("date", "核算日期", "text"),
                new AnalysisDtos.Column("product", "产品", "text"),
                new AnalysisDtos.Column("qty", "数量", "number"),
                new AnalysisDtos.Column("footprint", "碳足迹", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("boundary", "边界", "text")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    private AnalysisDtos.View emptyFootprintView() {
        return new AnalysisDtos.View(List.of(), List.of(), new AnalysisDtos.Table("足迹记录", List.of(
                new AnalysisDtos.Column("date", "核算日期", "text"),
                new AnalysisDtos.Column("product", "产品", "text"),
                new AnalysisDtos.Column("qty", "数量", "number"),
                new AnalysisDtos.Column("footprint", "碳足迹", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("boundary", "边界", "text")), List.of()), null);
    }

    /* ==================== 8. 供应链碳管理分析 ==================== */

    /**
     * 供应链碳管理分析。筛选供应商时按「某供应商及其全部下级」取数，
     * 因为在供应链语义下选中一级供应商就是要看整条链，而不是只看它自己。
     */
    public AnalysisDtos.View supplyChainAnalysis(String supplier, Integer year) {
        boolean filtered = (supplier != null && !supplier.isBlank()) || year != null;
        List<Supplier> all = suppliers.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0));
        Map<Long, Supplier> byId = new HashMap<>();
        for (Supplier s : all) byId.put(s.getId(), s);

        // 选中供应商时把范围收敛到它 + 全部下级
        Set<Long> scope = null;
        if (supplier != null && !supplier.isBlank()) {
            Supplier root = all.stream()
                    .filter(s -> supplier.equals(s.getName()))
                    .findFirst().orElse(null);
            if (root == null) return emptySupplyView();
            scope = subtreeIds(all, root.getId());
        }
        final Set<Long> scopeIds = scope;

        Comparator<Supplier> order = Comparator
                .comparing((Supplier s) -> s.getFootprint() == null ? BigDecimal.ZERO : s.getFootprint())
                .reversed()
                .thenComparing(s -> s.getSort() == null ? 0 : s.getSort())
                .thenComparing(Supplier::getId);
        List<Supplier> list = all.stream()
                .filter(s -> scopeIds == null || scopeIds.contains(s.getId()))
                .filter(s -> year == null || (s.getDate() != null && s.getDate().getYear() == year))
                .sorted(order)
                .toList();
        if (list.isEmpty()) {
            if (filtered) return emptySupplyView();
            throw BizException.notFound("暂无供应链足迹数据");
        }

        BigDecimal total = list.stream().map(Supplier::getFootprint).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Supplier top = list.get(0);
        long level1 = list.stream().filter(s -> "一级".equals(s.getLevel())).count();
        long deeper = list.size() - level1;

        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi("供应商", String.valueOf(list.size()), "家", "有足迹数据"),
                new AnalysisDtos.Kpi("足迹总量", fmt(total, 4), "tCO2e",
                        "按 " + list.size() + " 家供应商足迹求和"),
                new AnalysisDtos.Kpi("最高足迹", top.getName(), "",
                        fmt(top.getFootprint(), 4) + " " + top.getFpUnit()),
                new AnalysisDtos.Kpi("一级供应商", String.valueOf(level1), "家", "二级及以下 " + deeper + " 家")
        );
        List<Supplier> top8 = list.stream().limit(8).toList();
        // 图表只画前 8 家，KPI 按全量求和：名称里带 Top 8，避免口径误读
        String seriesName = list.size() > 8 ? "供应商碳足迹（Top 8）" : "供应商碳足迹";
        List<AnalysisDtos.Series> series = List.of(new AnalysisDtos.Series(
                seriesName, "bar", "tCO2e/单位",
                top8.stream().map(Supplier::getName).toList(),
                top8.stream().map(s -> scale(s.getFootprint(), 4)).toList()));
        List<Map<String, Object>> rows = list.stream().map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            // 图表用的父子标识，列定义里不展示
            r.put("id", s.getId());
            r.put("pid", s.getParentId());
            r.put("supplier", s.getName());
            r.put("path", supplierPath(s, byId));
            r.put("product", s.getProduct());
            r.put("footprint", scale(s.getFootprint(), 4));
            r.put("unit", s.getFpUnit());
            r.put("level", s.getLevel());
            r.put("date", s.getDate() == null ? "" : s.getDate().toString());
            return r;
        }).toList();
        AnalysisDtos.Table table = new AnalysisDtos.Table("供应链足迹", List.of(
                new AnalysisDtos.Column("supplier", "供应商", "text"),
                new AnalysisDtos.Column("path", "层级路径", "text"),
                new AnalysisDtos.Column("product", "供应产品", "text"),
                new AnalysisDtos.Column("footprint", "碳足迹", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("level", "层级", "text"),
                new AnalysisDtos.Column("date", "核算日期", "text")), rows);
        return new AnalysisDtos.View(kpis, series, table, null);
    }

    /** 供应商到根节点的名称路径，如「苏州精铸 › 吴江精密」。 */
    /** 收集某供应商及其全部下级；用 visited 防住历史脏数据造成的环。 */
    private Set<Long> subtreeIds(List<Supplier> all, Long rootId) {
        Map<Long, List<Supplier>> children = new HashMap<>();
        for (Supplier s : all) {
            if (s.getParentId() != null) {
                children.computeIfAbsent(s.getParentId(), k -> new ArrayList<>()).add(s);
            }
        }
        Set<Long> ids = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (id == null || !ids.add(id)) continue;
            for (Supplier child : children.getOrDefault(id, List.of())) {
                queue.add(child.getId());
            }
        }
        return ids;
    }

    private String supplierPath(Supplier s, Map<Long, Supplier> byId) {
        List<String> names = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Supplier cur = s;
        while (cur != null && seen.add(cur.getId())) {
            names.add(0, cur.getName());
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return String.join(" › ", names);
    }

    private AnalysisDtos.View emptySupplyView() {
        return new AnalysisDtos.View(List.of(), List.of(), new AnalysisDtos.Table("供应链足迹", List.of(
                new AnalysisDtos.Column("supplier", "供应商", "text"),
                new AnalysisDtos.Column("path", "层级路径", "text"),
                new AnalysisDtos.Column("product", "供应产品", "text"),
                new AnalysisDtos.Column("footprint", "碳足迹", "number"),
                new AnalysisDtos.Column("unit", "单位", "text"),
                new AnalysisDtos.Column("level", "层级", "text"),
                new AnalysisDtos.Column("date", "核算日期", "text")), List.of()), null);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /* ==================== 首页聚合 ==================== */

    public AnalysisDtos.Overview overview(Integer year) {
        int y = year == null ? YearMonth.now().getYear() : year;
        Stats s = stats(new Range(YearMonth.of(y, 1), YearMonth.of(y, 12)));
        Stats lastYear = stats(new Range(YearMonth.of(y - 1, 1), YearMonth.of(y - 1, 12)));
        BigDecimal output = outputValue(String.valueOf(y));
        BigDecimal quota = quotaTotal(y);
        BigDecimal usage = EnergyMath.ratio(s.totalEmission, quota);
        BigDecimal intensity = output.signum() == 0 ? BigDecimal.ZERO
                : s.totalEmission.divide(output, 4, RoundingMode.HALF_UP);

        List<AnalysisDtos.MonthPoint> monthly = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(y, m);
            monthly.add(new AnalysisDtos.MonthPoint(ym.toString(), scale(s.monthTce(ym), 2),
                    scale(s.monthEmission(ym), 2)));
        }

        List<AnalysisDtos.Todo> todos = new ArrayList<>();
        periods.selectList(Wrappers.<AccountingPeriod>lambdaQuery()
                        .eq(AccountingPeriod::getOrgId, CurrentUser.orgId())
                        .likeRight(AccountingPeriod::getPeriod, String.valueOf(y))
                        .in(AccountingPeriod::getStatus, List.of("calculated", "submitted"))
                        .orderByAsc(AccountingPeriod::getPeriod))
                .forEach(p -> todos.add(new AnalysisDtos.Todo(
                        p.getPeriod() + " 核算" + ("calculated".equals(p.getStatus()) ? "待提交" : "待锁定"),
                        "当前状态：" + p.getStatus(), "calculated".equals(p.getStatus()) ? "去提交" : "去锁定",
                        "/carbon-computation/calculation-complete/list")));
        List<Device> offline = devices.selectList(Wrappers.<Device>lambdaQuery()
                .eq(Device::getOrgId, CurrentUser.orgId())
                .eq(Device::getStatus, "offline"));
        if (!offline.isEmpty()) {
            todos.add(new AnalysisDtos.Todo(offline.size() + " 台设备离线超过阈值",
                    String.join("、", offline.stream().map(Device::getName).limit(3).toList()),
                    "查看设备", "/carbon-facility/device/list"));
        }
        Optional<YearMonth> latestWithData = s.byMonth.keySet().stream()
                .filter(m -> s.monthTce(m).signum() > 0).max(YearMonth::compareTo);
        latestWithData.ifPresent(m -> {
            Long count = reports.selectCount(Wrappers.<Report>lambdaQuery()
                    .eq(Report::getOrgId, CurrentUser.orgId())
                    .eq(Report::getDisabled, 0)
                    .eq(Report::getReportType, "carbon")
                    .eq(Report::getPeriodLabel, m.toString()));
            if (count == null || count == 0) {
                todos.add(new AnalysisDtos.Todo(m + " 碳排放报告未生成", "有读数但未生成报告",
                        "去生成", "/carbon-report/calculation-report/query"));
            }
        });

        List<AnalysisDtos.AlertBrief> alertBriefs = alerts.selectList(Wrappers.<AlertRecord>lambdaQuery()
                        .eq(AlertRecord::getOrgId, CurrentUser.orgId())
                        .eq(AlertRecord::getDisabled, 0)
                        .orderByDesc(AlertRecord::getOccurredAt)
                        .last("LIMIT 4"))
                .stream().map(a -> new AnalysisDtos.AlertBrief(a.getId(), a.getTitle(), a.getLevel(),
                        a.getMetricValue() == null ? "" : a.getMetricValue().toPlainString() + (a.getUnit() == null ? "" : a.getUnit()),
                        a.getOccurredAt() == null ? "" : a.getOccurredAt().toString(), a.getStatus()))
                .toList();

        Org org = orgs.selectById(CurrentUser.orgId());
        List<AnalysisDtos.Kpi> kpis = List.of(
                new AnalysisDtos.Kpi(y + "年综合能耗", fmt(s.totalTce, 2), "tce", yoy(s.totalTce, lastYear.totalTce)),
                new AnalysisDtos.Kpi(y + "年碳排放总量", fmt(s.totalEmission, 2), "tCO2e",
                        yoy(s.totalEmission, lastYear.totalEmission)),
                new AnalysisDtos.Kpi("单位产值碳排放", fmt(intensity, 4), "tCO2e/万元",
                        "产值 " + fmt(output, 2) + " 万元"),
                new AnalysisDtos.Kpi("配额使用率", quota.signum() == 0 ? "—" : usage.toPlainString(), "%",
                        "配额 " + fmt(quota, 2) + " tCO2e")
        );
        AnalysisDtos.Quota quotaView = new AnalysisDtos.Quota(scale(quota, 2),
                scale(s.totalEmission, 2), usage, scale(quota.subtract(s.totalEmission), 2));
        return new AnalysisDtos.Overview(CurrentUser.orgId(),
                org == null ? "" : org.getName(), org == null ? "" : org.getShortName(),
                y, kpis, monthly, todos.stream().limit(5).toList(), alertBriefs, quotaView);
    }

    /* ==================== 内部：聚合 ==================== */

    private void addRec(List<Map<String, Object>> rows, List<String> high,
                        String title, String basis, String priority, String effect) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", title);
        r.put("basis", basis);
        r.put("priority", priority);
        r.put("effect", effect);
        rows.add(r);
        if ("高".equals(priority)) high.add(title);
    }

    private String level(BigDecimal intensity, BigDecimal l1, BigDecimal l2, BigDecimal l3) {
        if (l1 != null && intensity.compareTo(l1) <= 0) return "1级";
        if (l2 != null && intensity.compareTo(l2) <= 0) return "2级";
        if (l3 != null && intensity.compareTo(l3) <= 0) return "3级";
        return "待改进";
    }

    private BigDecimal quotaTotal(int year) {
        List<CarbonAsset> list = assets.selectList(Wrappers.<CarbonAsset>lambdaQuery()
                .eq(CarbonAsset::getOrgId, CurrentUser.orgId())
                .eq(CarbonAsset::getDisabled, 0)
                .eq(CarbonAsset::getAssetYear, year));
        BigDecimal quota = list.stream()
                .filter(a -> a.getName() != null && a.getName().contains("碳配额"))
                .map(CarbonAsset::getQty).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (quota.signum() > 0) return quota;
        return list.stream().filter(a -> "配额资产".equals(a.getType()))
                .map(CarbonAsset::getQty).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal outputValue(String year) {
        BigDecimal v = production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                        .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                        .eq(ProductionValue::getDisabled, 0)
                        .likeRight(ProductionValue::getPeriod, year))
                .stream().map(ProductionValue::getOutputValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (v.signum() > 0) return v;
        return totals.selectList(Wrappers.<TotalValue>lambdaQuery()
                        .eq(TotalValue::getOrgId, CurrentUser.orgId())
                        .eq(TotalValue::getDisabled, 0)
                        .likeRight(TotalValue::getPeriod, year))
                .stream().map(TotalValue::getOutputValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<YearMonth, BigDecimal> outputByMonth(Range range) {
        Map<YearMonth, BigDecimal> out = new TreeMap<>();
        range.months().forEach(m -> out.put(m, BigDecimal.ZERO));
        production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                        .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                        .eq(ProductionValue::getDisabled, 0)
                        .ge(ProductionValue::getPeriod, range.from.toString())
                        .le(ProductionValue::getPeriod, range.to.toString()))
                .forEach(pv -> {
                    if (pv.getOutputValue() == null) return;
                    out.merge(YearMonth.parse(pv.getPeriod()), pv.getOutputValue(), BigDecimal::add);
                });
        Map<YearMonth, BigDecimal> fallback = new HashMap<>();
        totals.selectList(Wrappers.<TotalValue>lambdaQuery()
                        .eq(TotalValue::getOrgId, CurrentUser.orgId())
                        .eq(TotalValue::getDisabled, 0)
                        .ge(TotalValue::getPeriod, range.from.toString())
                        .le(TotalValue::getPeriod, range.to.toString()))
                .forEach(tv -> {
                    if (tv.getOutputValue() == null) return;
                    fallback.merge(YearMonth.parse(tv.getPeriod()), tv.getOutputValue(), BigDecimal::add);
                });
        out.replaceAll((m, v) -> v.signum() == 0 ? fallback.getOrDefault(m, BigDecimal.ZERO) : v);
        return out;
    }

    private Stats stats(Range range) {
        return new Stats(range);
    }

    private final class Stats {
        final Range range;
        final Map<Long, Device> devices = new HashMap<>();
        final List<DeviceReading> readings;
        final Map<String, BigDecimal[]> byFuel = new LinkedHashMap<>(); // activity, tce, emission
        final Map<YearMonth, Map<String, BigDecimal[]>> byMonth = new TreeMap<>();
        BigDecimal totalTce = BigDecimal.ZERO;
        BigDecimal totalEmission = BigDecimal.ZERO;
        BigDecimal scope1 = BigDecimal.ZERO;
        BigDecimal scope2 = BigDecimal.ZERO;

        Stats(Range range) {
            this.range = range;
            AnalysisService.this.devices.selectList(Wrappers.<Device>lambdaQuery()
                            .eq(Device::getOrgId, CurrentUser.orgId()))
                    .forEach(d -> devices.put(d.getId(), d));
            this.readings = AnalysisService.this.readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                    .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                    .ge(DeviceReading::getTs, range.start())
                    .le(DeviceReading::getTs, range.end())
                    .eq(DeviceReading::getStatus, "正常")
                    .orderByAsc(DeviceReading::getTs));

            Map<String, EmissionFactor> factorCache = new HashMap<>();
            for (DeviceReading r : readings) {
                Device d = devices.get(r.getDeviceId());
                if (d == null || !EnergyMath.supported(d.getEnergyType())) continue;
                String fuel = d.getEnergyType();
                BigDecimal activity = EnergyMath.activity(r);
                BigDecimal tce = EnergyMath.tce(fuel, activity);
                EmissionFactor f = factor(fuel, YearMonth.from(r.getTs()), factorCache);
                BigDecimal emission = f == null ? BigDecimal.ZERO
                        : activity.multiply(f.getFactorValue()).setScale(4, RoundingMode.HALF_UP);

                BigDecimal[] agg = byFuel.computeIfAbsent(fuel,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                agg[0] = agg[0].add(activity);
                agg[1] = agg[1].add(tce);
                agg[2] = agg[2].add(emission);

                Map<String, BigDecimal[]> month = byMonth.computeIfAbsent(YearMonth.from(r.getTs()),
                        k -> new LinkedHashMap<>());
                BigDecimal[] ma = month.computeIfAbsent(fuel,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                ma[0] = ma[0].add(activity);
                ma[1] = ma[1].add(tce);
                ma[2] = ma[2].add(emission);

                totalTce = totalTce.add(tce);
                totalEmission = totalEmission.add(emission);
                if (SCOPE2_FUELS.contains(fuel)) scope2 = scope2.add(emission);
                else scope1 = scope1.add(emission);
            }
        }

        private EmissionFactor factor(String fuel, YearMonth ym, Map<String, EmissionFactor> cache) {
            String key = fuel + "|" + ym;
            if (cache.containsKey(key)) return cache.get(key);
            EmissionFactor f = AnalysisService.this.factors
                    .pick(fuel, ym.atDay(1), ym.atEndOfMonth()).orElse(null);
            cache.put(key, f);
            return f;
        }

        List<String> labels() {
            return range.months().stream().map(YearMonth::toString).toList();
        }

        BigDecimal fuelTce(String fuel) {
            BigDecimal[] v = byFuel.get(fuel);
            return v == null ? BigDecimal.ZERO : v[1];
        }

        BigDecimal[] monthFuel(YearMonth m, String fuel) {
            Map<String, BigDecimal[]> month = byMonth.get(m);
            if (month == null) return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            return month.getOrDefault(fuel, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }

        BigDecimal monthTce(YearMonth m) {
            Map<String, BigDecimal[]> month = byMonth.get(m);
            if (month == null) return BigDecimal.ZERO;
            return month.values().stream().map(v -> v[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal monthEmission(YearMonth m) {
            Map<String, BigDecimal[]> month = byMonth.get(m);
            if (month == null) return BigDecimal.ZERO;
            return month.values().stream().map(v -> v[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private record Range(YearMonth from, YearMonth to) {
        LocalDateTime start() {
            return from.atDay(1).atStartOfDay();
        }

        LocalDateTime end() {
            return to.atEndOfMonth().atTime(23, 59, 59);
        }

        List<YearMonth> months() {
            List<YearMonth> list = new ArrayList<>();
            for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) list.add(m);
            return list;
        }

        String label() {
            return from + " ~ " + to;
        }
    }

    private Range range(String from, String to) {
        YearMonth toM = parseMonth(to, YearMonth.now());
        YearMonth fromM = parseMonth(from, toM.minusMonths(2));
        if (fromM.isAfter(toM)) throw BizException.validation("开始月份不能晚于结束月份");
        if (ChronoUnit.MONTHS.between(fromM, toM) > 23) throw BizException.validation("分析区间不超过 24 个月");
        return new Range(fromM, toM);
    }

    private YearMonth parseMonth(String v, YearMonth fallback) {
        if (v == null || v.isBlank()) return fallback;
        try {
            return YearMonth.parse(v);
        } catch (DateTimeParseException e) {
            throw BizException.validation("月份格式为 YYYY-MM");
        }
    }

    private Map<String, Object> totalRow(String name, BigDecimal tce) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fuel", name);
        r.put("activity", "");
        r.put("unit", "");
        r.put("tce", scale(tce, 2));
        r.put("share", HUNDRED.setScale(2));
        return r;
    }

    private String yoy(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) return "无上年同期数据";
        BigDecimal pct = now.subtract(before).multiply(HUNDRED).divide(before, 1, RoundingMode.HALF_UP);
        return "较上年同期 " + (pct.signum() >= 0 ? "+" : "") + pct + "%";
    }

    private BigDecimal scale(BigDecimal v, int digits) {
        return (v == null ? BigDecimal.ZERO : v).setScale(digits, RoundingMode.HALF_UP);
    }

    private String fmt(BigDecimal v, int digits) {
        return (v == null ? BigDecimal.ZERO : v).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }
}
