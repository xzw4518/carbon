package com.carbonbal.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.asset.AssetDtos;
import com.carbonbal.asset.AssetService;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import com.carbonbal.facility.Facility;
import com.carbonbal.facility.FacilityDtos;
import com.carbonbal.facility.FacilityMapper;
import com.carbonbal.facility.FacilityService;
import com.carbonbal.output.OutputDtos;
import com.carbonbal.output.OutputService;
import com.carbonbal.product.MasterDataDtos;
import com.carbonbal.product.Product;
import com.carbonbal.product.ProductMapper;
import com.carbonbal.product.ProductService;
import com.carbonbal.product.SupplierService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账批量导入：统一入口 {@code POST /api/import?resource=}。
 * 逐行调用既有 Service，保持与手工录入完全一致的校验与审计。
 */
@Service
public class ImportService {
    private final ProductService products;
    private final ProductMapper productMapper;
    private final OutputService output;
    private final AssetService assets;
    private final SupplierService suppliers;
    private final FacilityService facilities;
    private final FacilityMapper facilityMapper;

    public ImportService(ProductService products, ProductMapper productMapper, OutputService output,
                         AssetService assets, SupplierService suppliers,
                         FacilityService facilities, FacilityMapper facilityMapper) {
        this.products = products;
        this.productMapper = productMapper;
        this.output = output;
        this.assets = assets;
        this.suppliers = suppliers;
        this.facilities = facilities;
        this.facilityMapper = facilityMapper;
    }

    public record Result(int success, int failed, List<Map<String, Object>> errors,
                         List<Map<String, Object>> warnings) {}

    public record Template(String name, byte[] bytes) {}

    public List<String[]> read(MultipartFile file) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) throw BizException.validation("文件为空");
            int no = 1;
            while ((line = br.readLine()) != null) {
                no++;
                if (line.isBlank()) continue;
                rows.add(new String[]{String.valueOf(no), line});
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.validation("文件解析失败：" + e.getMessage());
        }
        return rows;
    }

    /**
     * 各资源表头必须匹配的前缀列（模板的前几列）。
     * 只校验前缀：允许用户少填末尾可选列，但拦住“拿错文件/没有表头”的情况——
     * 否则首行会被当成表头丢掉，错误列序会静默写成脏数据。
     */
    private static final Map<String, String[]> HEADER_PREFIX = Map.of(
            "products", new String[]{"编码", "名称"},
            "production-values", new String[]{"月份", "产品编码", "产值"},
            "carbon-assets", new String[]{"年度", "资产名称", "资产类型"},
            "suppliers", new String[]{"供应商名称", "产品名称", "产品单位", "碳足迹值"},
            "facilities", new String[]{"设施编码", "设施名称"});

    private void assertHeader(String resource, MultipartFile file) {
        String[] want = HEADER_PREFIX.get(resource);
        if (want == null) return;
        String line;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            line = br.readLine();
        } catch (Exception e) {
            throw BizException.validation("文件解析失败：" + e.getMessage());
        }
        if (line == null) throw BizException.validation("文件为空");
        String[] cells = line.replace("\uFEFF", "").trim().split(",", -1);
        for (int i = 0; i < want.length; i++) {
            String got = i < cells.length ? cells[i].trim() : "";
            if (!want[i].equals(got)) {
                throw BizException.validation("表头不正确，请下载模板后填写（前 " + want.length
                        + " 列应为：" + String.join("、", want) + "）");
            }
        }
    }

    /** 不加事务：逐行独立提交，单行失败不影响其他行。 */
    public Result run(String resource, MultipartFile file) {
        String r = resource == null ? "" : resource;
        assertHeader(r, file);
        return switch (r) {
            case "products" -> products(file);
            case "production-values" -> production(file);
            case "carbon-assets" -> carbonAssets(file);
            case "suppliers" -> supplierRows(file);
            case "facilities" -> facilityRows(file);
            default -> throw BizException.validation("不支持的导入资源: " + r);
        };
    }

    public Template template(String resource) {
        String header = switch (resource == null ? "" : resource) {
            case "products" -> "编码,名称,规格型号,产品类型,产品分类,单位,1级能耗,2级能耗,3级能耗";
            case "production-values" -> "月份,产品编码,产值,产值单位,产量,产量单位";
            case "carbon-assets" -> "年度,资产名称,资产类型,数量,单位";
            case "suppliers" -> "供应商名称,产品名称,产品单位,碳足迹值,碳足迹单位,碳足迹日期,核算范围,上级供应商";
            case "facilities" -> "设施编码,设施名称,上级设施编码,排序,备注";
            default -> throw BizException.validation("不支持的导入资源: " + resource);
        };
        String content = "\uFEFF" + header + "\r\n";
        return new Template(resource + "_模板.csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private Result loop(MultipartFile file, RowHandler handler) {
        List<String[]> rows = read(file);
        int expected = headerColumns(file);
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        int ok = 0, failed = 0;
        for (String[] row : rows) {
            int no = Integer.parseInt(row[0]);
            String[] c = row[1].split(",", -1);
            if (expected > 0 && c.length < expected) {
                // 少填的列会被按默认值补齐，必须显式告警，避免"看起来导入成功"
                warnings.add(Map.of("line", no, "reason",
                        "该行只有 " + c.length + " 列（模板 " + expected + " 列），缺少的字段已按默认值补齐"));
            } else if (expected > 0 && c.length > expected) {
                warnings.add(Map.of("line", no, "reason",
                        "该行有 " + c.length + " 列（模板 " + expected + " 列），列数不一致，请检查多余逗号或引号"));
            }
            try {
                handler.handle(c);
                ok++;
            } catch (Exception e) {
                failed++;
                errors.add(Map.of("line", no, "reason",
                        e.getMessage() == null ? "解析失败" : e.getMessage()));
            }
        }
        return new Result(ok, failed, errors, warnings);
    }

    /** 表头列数：用于判断数据行是否缺列（读不到表头时返回 0，不产生告警）。 */
    private int headerColumns(MultipartFile file) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return 0;
            return line.replace("\uFEFF", "").trim().split(",", -1).length;
        } catch (Exception e) {
            return 0;
        }
    }

    private interface RowHandler {
        void handle(String[] c) throws Exception;
    }

    private String at(String[] c, int i) {
        return c.length > i ? c[i].trim() : "";
    }

    private String need(String v, String label) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(label + "为空");
        return v.trim();
    }

    private Result products(MultipartFile file) {
        return loop(file, c -> {
            String code = need(at(c, 0), "编码");
            String name = need(at(c, 1), "名称");
            products.create(new MasterDataDtos.ProductReq(name, code, at(c, 2),
                    at(c, 3).isBlank() ? "一般产品" : at(c, 3),
                    at(c, 4).isBlank() ? "工业产品" : at(c, 4),
                    at(c, 5).isBlank() ? "吨" : at(c, 5),
                    dec(at(c, 6)), dec(at(c, 7)), dec(at(c, 8)), null, null));
        });
    }

    private Result production(MultipartFile file) {
        return loop(file, c -> {
            String period = need(at(c, 0), "月份");
            String productKey = need(at(c, 1), "产品编码");
            Long productId;
            Product p = productMapper.selectOne(Wrappers.<Product>lambdaQuery()
                    .eq(Product::getCode, productKey).eq(Product::getDisabled, 0).last("LIMIT 1"));
            if (p != null) productId = p.getId();
            else {
                Product byName = productMapper.selectOne(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getName, productKey).eq(Product::getDisabled, 0).last("LIMIT 1"));
                if (byName == null) throw new IllegalArgumentException("产品不存在: " + productKey);
                productId = byName.getId();
            }
            output.createProduction(new OutputDtos.ProductionReq(period, productId,
                    dec(need(at(c, 2), "产值")), at(c, 3).isBlank() ? "万元" : at(c, 3),
                    dec(at(c, 4)), at(c, 5).isBlank() ? "吨" : at(c, 5), null, null));
        });
    }

    private Result carbonAssets(MultipartFile file) {
        return loop(file, c -> {
            assets.create(new AssetDtos.AssetReq(
                    need(at(c, 1), "资产名称"),
                    at(c, 2).isBlank() ? "配额资产" : at(c, 2),
                    dec(need(at(c, 3), "数量")),
                    at(c, 4).isBlank() ? "tCO2e" : at(c, 4),
                    intOf(need(at(c, 0), "年度"), "年度"), "正常", null, null, null));
        });
    }

    private Result supplierRows(MultipartFile file) {
        // 两阶段：先建节点（全部为根），再按"上级供应商"名称绑定，避免父行排在子行之后
        Map<String, Long> nameToId = new LinkedHashMap<>();
        Map<Long, String> pendingParent = new LinkedHashMap<>();
        Result result = loop(file, c -> {
            String date = at(c, 5).isBlank() ? LocalDate.now().toString() : at(c, 5);
            String scope = at(c, 6);
            if (!scope.isBlank() && !java.util.List.of("范围一", "范围二", "范围三").contains(scope)) {
                throw new IllegalArgumentException("核算范围只能是 范围一/范围二/范围三");
            }
            com.carbonbal.product.Supplier created = suppliers.create(new MasterDataDtos.SupplierReq(
                    need(at(c, 0), "供应商名称"), need(at(c, 1), "产品名称"),
                    at(c, 2).isBlank() ? "吨" : at(c, 2),
                    at(c, 4).isBlank() ? "tCO2e/t" : at(c, 4),
                    dec(need(at(c, 3), "碳足迹值")), date(date.startsWith("20") ? date : LocalDate.now().toString(), "碳足迹日期"),
                    scope.isBlank() ? null : scope, null, 1, null, null, null));
            nameToId.putIfAbsent(created.getName(), created.getId());
            String parentName = at(c, 7);
            if (!parentName.isBlank()) pendingParent.put(created.getId(), parentName);
        });
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (Map.Entry<Long, String> e : pendingParent.entrySet()) {
            Long pid = nameToId.get(e.getValue());
            if (pid == null) {
                // 上级名称在本批次或库中都不存在：保留为一级，但要明确告知，避免层级静默错乱
                warnings.add(Map.of("supplierId", e.getKey(), "reason",
                        "上级供应商「" + e.getValue() + "」不存在，已按一级供应商导入"));
                continue;
            }
            if (pid.equals(e.getKey())) continue;
            try {
                suppliers.assignParent(e.getKey(), pid);
            } catch (Exception ex) {
                warnings.add(Map.of("supplierId", e.getKey(), "reason",
                        "上级供应商「" + e.getValue() + "」不可用（成环或超出五级），已按一级供应商导入"));
            }
        }
        List<Map<String, Object>> allWarnings = new ArrayList<>(result.warnings());
        allWarnings.addAll(warnings);
        return new Result(result.success(), result.failed(), result.errors(), allWarnings);
    }

    private Result facilityRows(MultipartFile file) {
        Map<String, Long> byCode = new HashMap<>();
        facilityMapper.selectList(Wrappers.<Facility>lambdaQuery().eq(Facility::getDisabled, 0))
                .forEach(f -> byCode.put(f.getCode(), f.getId()));
        return loop(file, c -> {
            String parentCode = at(c, 2);
            Long parentId = parentCode.isBlank() ? null : byCode.get(parentCode);
            if (!parentCode.isBlank() && parentId == null) {
                throw new IllegalArgumentException("上级设施编码不存在: " + parentCode);
            }
            FacilityDtos.Req req = new FacilityDtos.Req(need(at(c, 1), "设施名称"),
                    need(at(c, 0), "设施编码"), parentId,
                    at(c, 3).isBlank() ? 1 : intOf(at(c, 3), "排序"), at(c, 4), null);
            facilities.create(req);
            List<Facility> created = facilityMapper.selectList(Wrappers.<Facility>lambdaQuery()
                    .eq(Facility::getCode, at(c, 0)).eq(Facility::getDisabled, 0));
            created.forEach(f -> byCode.put(f.getCode(), f.getId()));
        });
    }

    private java.math.BigDecimal dec(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值格式不正确：" + v.trim());
        }
    }

    private int intOf(String v, String label) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "必须是整数：" + v.trim());
        }
    }

    /** 友好日期解析：避免把 DateTimeParseException 的英文内部信息回显给用户。 */
    private LocalDate date(String v, String label) {
        try {
            return LocalDate.parse(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "格式不正确：" + v.trim() + "（应为 yyyy-MM-dd）");
        }
    }

    /** 供控制器判断资源是否被支持 */
    public void assertSupported(String resource) {
        if (!List.of("products", "production-values", "carbon-assets", "suppliers", "facilities")
                .contains(resource == null ? "" : resource)) {
            throw BizException.validation("不支持的导入资源: " + resource);
        }
    }
}
