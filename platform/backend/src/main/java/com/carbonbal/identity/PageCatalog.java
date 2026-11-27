package com.carbonbal.identity;

import java.util.List;
import java.util.Set;

/**
 * 页面目录：角色勾选的就是这里的页面。
 * <p>
 * 编码一旦发布不得修改（角色绑定按 code 存储）；新增页面只需在 {@link #GROUPS} 追加，
 * 默认角色会在下次启动时自动补齐。
 */
public final class PageCatalog {

    public record Item(String code, String name) {}

    public record Group(String name, List<Item> pages) {}

    public static final List<Group> GROUPS = List.of(
            new Group("首页", List.of(
                    new Item("home", "首页"))),
            new Group("企业管理", List.of(
                    new Item("enterprise.info", "企业信息"),
                    new Item("group.overview", "集团汇总"))),
            new Group("预算管理", List.of(
                    new Item("quota.list", "碳排放配额"))),
            new Group("产值管理", List.of(
                    new Item("product.list", "产品列表"),
                    new Item("production.value", "产品产值"),
                    new Item("total.value", "生产总值"))),
            new Group("碳排放核算", List.of(
                    new Item("accounting.list", "碳排放核算"))),
            new Group("碳足迹管理", List.of(
                    new Item("footprint.list", "碳足迹核算"),
                    new Item("footprint.analysis", "碳足迹分析"),
                    new Item("supplier.list", "供应链碳管理"),
                    new Item("supplier.analysis", "供应链分析"))),
            new Group("碳资产管理", List.of(
                    new Item("asset.list", "碳配额资产"),
                    new Item("ccer.list", "信用资产"),
                    new Item("trade.list", "交易管理"))),
            new Group("碳核查支撑", List.of(
                    new Item("evidence.list", "原始凭证"),
                    new Item("third.report", "第三方报告"))),
            new Group("报告查询", List.of(
                    new Item("report.carbon", "碳排放报告"),
                    new Item("report.energy", "能源利用报告"),
                    new Item("report.footprint", "产品碳足迹报告"))),
            new Group("能碳分析", List.of(
                    new Item("analysis.energy", "能耗查询"),
                    new Item("analysis.strategy", "策略推荐"),
                    new Item("analysis.benchmark", "能效对标"),
                    new Item("analysis.carbon", "碳排放查询"),
                    new Item("analysis.quota", "碳配额核算"),
                    new Item("analysis.flow", "能流分析"))),
            new Group("设备设施", List.of(
                    new Item("facility.list", "设施管理"),
                    new Item("device.list", "设备管理"),
                    new Item("device.data", "设备数据"))),
            new Group("预警管理", List.of(
                    new Item("alert.event", "事件预警"),
                    new Item("alert.setting", "预警设置"),
                    new Item("alert.record", "预警记录"))),
            new Group("节能管理", List.of(
                    new Item("retrofit.list", "节能改造项目"),
                    new Item("energy.mgmt", "能源管理情况")))
    );

    public static final List<String> CODES = GROUPS.stream()
            .flatMap(g -> g.pages().stream()).map(Item::code).toList();

    public static final Set<String> ALL = Set.copyOf(CODES);

    /**
     * 特权页面：涉及跨企业数据（集团汇总），默认角色<b>不</b>自动拥有，必须由租户管理员显式授予。
     * <p>
     * 只有"特权页面"这层页面级门禁还不够：被授予页面的普通成员也只能看到自己所属企业，
     * 跨企业展开由 {@code GroupService} 再做一次"父企业管理员身份"校验（P1-1 双层校验）。
     */
    public static final Set<String> PRIVILEGED = Set.of("group.overview");

    /** 默认角色自动补齐页面时使用的白名单：全部页面去掉特权页面。 */
    public static final List<String> DEFAULT_ROLE_CODES = CODES.stream()
            .filter(c -> !PRIVILEGED.contains(c)).toList();

    /**
     * 是否为特权页面。
     *
     * @param code 页面编码，可为 null
     * @return 是特权页面返回 true
     */
    public static boolean isPrivileged(String code) {
        return code != null && PRIVILEGED.contains(code);
    }

    /** 接口前缀 → 页面编码；按顺序匹配，最具体的放前面。 */
    private record Rule(String prefix, String code) {}

    private static final List<Rule> RULES = List.of(
            new Rule("/api/bootstrap", "home"),
            new Rule("/api/overview", "home"),
            new Rule("/api/group", "group.overview"),
            new Rule("/api/org/license", "enterprise.info"),
            new Rule("/api/org", "enterprise.info"),
            new Rule("/api/quota-years", "quota.list"),
            new Rule("/api/products", "product.list"),
            new Rule("/api/production-values", "production.value"),
            new Rule("/api/total-values", "total.value"),
            new Rule("/api/accounting-periods", "accounting.list"),
            new Rule("/api/analysis/footprint", "footprint.analysis"),
            new Rule("/api/analysis/supply-chain", "supplier.analysis"),
            new Rule("/api/analysis/energy-consumption", "analysis.energy"),
            new Rule("/api/analysis/energy-efficiency", "analysis.benchmark"),
            new Rule("/api/analysis/energy-flow", "analysis.flow"),
            new Rule("/api/analysis/strategy", "analysis.strategy"),
            new Rule("/api/analysis/quota", "analysis.quota"),
            new Rule("/api/analysis/carbon-emission", "analysis.carbon"),
            new Rule("/api/analysis", "analysis.carbon"),
            new Rule("/api/emission-factors", "accounting.list"),
            new Rule("/api/footprints", "footprint.list"),
            new Rule("/api/suppliers", "supplier.list"),
            new Rule("/api/carbon-assets", "asset.list"),
            new Rule("/api/carbon-trades", "trade.list"),
            new Rule("/api/evidences", "evidence.list"),
            new Rule("/api/third-party-reports", "third.report"),
            new Rule("/api/reports", "report.carbon"),
            new Rule("/api/facilities", "facility.list"),
            new Rule("/api/devices", "device.list"),
            new Rule("/api/device-readings", "device.data"),
            new Rule("/api/alert-events", "alert.event"),
            new Rule("/api/alert-rules", "alert.setting"),
            new Rule("/api/alert-records", "alert.record"),
            new Rule("/api/retrofits", "retrofit.list"),
            new Rule("/api/energy-mgmt", "energy.mgmt")
    );

    private PageCatalog() {}

    /** 请求所属页面；未登记返回 null（交给 @PreAuthorize 兜底）。 */
    public static String pageForUri(String uri) {
        if (uri == null) return null;
        for (Rule r : RULES) {
            if (uri.equals(r.prefix()) || uri.startsWith(r.prefix() + "/")
                    || uri.startsWith(r.prefix() + "?")) {
                return r.code();
            }
        }
        return null;
    }

    public static boolean exists(String code) {
        return code != null && ALL.contains(code);
    }

    /**
     * 导出资源 → 页面编码。导出接口不走 URI 前缀规则（统一 /api/export），
     * 必须按 resource 逐项校验，否则会绕过页面权限拿到别的模块数据。
     */
    public static String pageForExportResource(String resource) {
        if (resource == null) return null;
        return switch (resource) {
            case "readings" -> "device.data";
            case "production-values" -> "production.value";
            case "total-values" -> "total.value";
            case "evidences" -> "evidence.list";
            case "third-party-reports" -> "third.report";
            case "facilities" -> "facility.list";
            case "products" -> "product.list";
            case "suppliers" -> "supplier.list";
            case "carbon-assets" -> "asset.list";
            case "credit-assets" -> "ccer.list";
            case "retrofits" -> "retrofit.list";
            case "energy-mgmt" -> "energy.mgmt";
            case "carbon-trades" -> "trade.list";
            case "footprints" -> "footprint.list";
            case "alert-records" -> "alert.record";
            case "devices" -> "device.list";
            default -> null;
        };
    }

    /** 导入资源 → 页面编码；与导出同样需要逐项校验。 */
    public static String pageForImportResource(String resource) {
        if (resource == null) return null;
        return switch (resource) {
            case "products" -> "product.list";
            case "production-values" -> "production.value";
            case "carbon-assets" -> "asset.list";
            case "credit-assets" -> "ccer.list";
            case "retrofits" -> "retrofit.list";
            case "energy-mgmt" -> "energy.mgmt";
            case "suppliers" -> "supplier.list";
            case "facilities" -> "facility.list";
            default -> null;
        };
    }
}
