package com.carbonbal.report;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.accounting.AccountingDtos;
import com.carbonbal.accounting.AccountingService;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.EnergyMath;
import com.carbonbal.common.PageResult;
import com.carbonbal.device.Device;
import com.carbonbal.device.DeviceMapper;
import com.carbonbal.device.DeviceReading;
import com.carbonbal.device.DeviceReadingMapper;
import com.carbonbal.factor.EmissionFactor;
import com.carbonbal.factor.FactorService;
import com.carbonbal.footprint.ProductFootprint;
import com.carbonbal.footprint.ProductFootprintMapper;
import com.carbonbal.output.ProductionValue;
import com.carbonbal.output.ProductionValueMapper;
import com.carbonbal.output.TotalValue;
import com.carbonbal.output.TotalValueMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class ReportService {
    private final ReportMapper reports;
    private final AccountingService accounting;
    private final PdfBuilder pdf;
    private final AuditService audit;
    private final ProductFootprintMapper footprints;
    private final DeviceReadingMapper readings;
    private final DeviceMapper devices;
    private final FactorService factors;
    private final ProductionValueMapper production;
    private final TotalValueMapper totals;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ReportService(ReportMapper reports, AccountingService accounting, PdfBuilder pdf,
                         AuditService audit, ProductFootprintMapper footprints,
                         DeviceReadingMapper readings, DeviceMapper devices, FactorService factors,
                         ProductionValueMapper production, TotalValueMapper totals,
                         org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.reports = reports;
        this.accounting = accounting;
        this.pdf = pdf;
        this.audit = audit;
        this.footprints = footprints;
        this.readings = readings;
        this.devices = devices;
        this.factors = factors;
        this.production = production;
        this.totals = totals;
        this.jdbc = jdbc;
    }

    @Transactional
    public ReportDtos.Meta generate(String type, String period, Long footprintId) {
        String t = (type == null || type.isBlank()) ? "carbon" : type;
        return switch (t) {
            case "carbon" -> generateCarbon(period);
            case "energy" -> generateEnergy(period);
            case "footprint" -> generateFootprint(footprintId);
            default -> throw BizException.validation("报告类型只能是 carbon / energy / footprint");
        };
    }

    private ReportDtos.Meta generateCarbon(String period) {
        requirePeriod(period);
        AccountingDtos.PeriodView p = accounting.get(period);
        if (p.id() == null || p.calcVersion() == null || p.calcVersion() == 0) {
            throw BizException.validation("请先完成核算再生成报告");
        }
        if (!submitted(p.status())) {
            throw BizException.locked("核算期间尚未提交，请先提交后再生成正式报告");
        }
        byte[] bytes = pdf.build(p, orgName(), CurrentUser.username());
        return persist("carbon", p.id(), p.id(), period, p.calcVersion(),
                "碳排放核算报告_" + period + "_v" + p.calcVersion() + ".pdf", bytes);
    }

    private ReportDtos.Meta generateEnergy(String period) {
        requirePeriod(period);
        AccountingDtos.PeriodView pv = accounting.get(period);
        if (pv != null && pv.id() != null && !submitted(pv.status())) {
            throw BizException.locked("核算期间尚未提交，请先提交后再生成正式报告");
        }
        YearMonth ym = YearMonth.parse(period);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(23, 59, 59);

        List<DeviceReading> rs = readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                .ge(DeviceReading::getTs, from)
                .le(DeviceReading::getTs, to)
                .eq(DeviceReading::getStatus, "正常"));
        if (rs.isEmpty()) throw BizException.validation("该月份没有可用的设备读数，无法生成能源报告");

        Map<Long, Device> devMap = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                .forEach(d -> devMap.put(d.getId(), d));

        Map<String, BigDecimal[]> agg = new LinkedHashMap<>(); // fuel -> [activity, tce, emission]
        Map<String, EmissionFactor> factorCache = new HashMap<>();
        for (DeviceReading r : rs) {
            Device d = devMap.get(r.getDeviceId());
            if (d == null || !EnergyMath.supported(d.getEnergyType())) continue;
            BigDecimal activity = EnergyMath.activity(r);
            BigDecimal tce = EnergyMath.tce(d.getEnergyType(), activity);
            EmissionFactor f = factorCache.computeIfAbsent(d.getEnergyType(),
                    k -> factors.pick(k, start, end).orElse(null));
            BigDecimal emission = f == null ? BigDecimal.ZERO
                    : activity.multiply(f.getFactorValue()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal[] a = agg.computeIfAbsent(d.getEnergyType(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(activity);
            a[1] = a[1].add(tce);
            a[2] = a[2].add(emission);
        }
        if (agg.isEmpty()) throw BizException.validation("该月份的读数不属于可折标能源品种，无法生成能源报告");

        BigDecimal totalTce = agg.values().stream().map(a -> a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEmission = agg.values().stream().map(a -> a[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outputValue = outputValue(period);

        List<ReportDtos.EnergyLine> lines = agg.entrySet().stream()
                .map(e -> new ReportDtos.EnergyLine(e.getKey(), e.getValue()[0].setScale(4, RoundingMode.HALF_UP),
                        EnergyMath.unit(e.getKey()), e.getValue()[1].setScale(4, RoundingMode.HALF_UP),
                        e.getValue()[2].setScale(4, RoundingMode.HALF_UP),
                        EnergyMath.ratio(e.getValue()[1], totalTce)))
                .sorted(Comparator.comparing(ReportDtos.EnergyLine::tce).reversed())
                .toList();

        ReportDtos.EnergySummary summary = new ReportDtos.EnergySummary(period, lines,
                totalTce.setScale(4, RoundingMode.HALF_UP),
                totalEmission.setScale(4, RoundingMode.HALF_UP), outputValue);
        byte[] bytes = pdf.buildEnergy(summary, orgName(), CurrentUser.username());
        Long periodId = accounting.get(period).id();
        return persist("energy", periodId, periodId, period, null,
                "能源利用报告_" + period + ".pdf", bytes);
    }

    private ReportDtos.Meta generateFootprint(Long footprintId) {
        if (footprintId == null) throw BizException.validation("产品碳足迹报告必须指定 footprintId");
        ProductFootprint f = footprints.selectById(footprintId);
        if (f == null || Integer.valueOf(1).equals(f.getDisabled())) throw BizException.notFound("足迹记录不存在");
        if (!f.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");

        byte[] bytes = pdf.buildFootprint(f, orgName(), CurrentUser.username());
        String month = f.getDate() == null ? "" : YearMonth.from(f.getDate()).toString();
        return persist("footprint", null, f.getId(), month, null,
                "产品碳足迹报告_" + f.getName() + "_" + f.getDate() + ".pdf", bytes);
    }

    private ReportDtos.Meta persist(String type, Long periodId, Long refId, String label,
                                    Integer calcVersion, String fileName, byte[] bytes) {
        String sha = sha256(bytes);
        Report r = new Report();
        r.setOrgId(CurrentUser.orgId());
        r.setReportType(type);
        r.setPeriodId(periodId);
        r.setRefId(refId);
        r.setPeriodLabel(label);
        r.setCalcVersion(calcVersion);
        r.setFileName(fileName);
        r.setFileSize(bytes.length);
        r.setSha256(sha);
        r.setContent(bytes);
        r.setDisabled(0);
        r.setGeneratedBy(CurrentUser.username());
        r.setGeneratedAt(LocalDateTime.now());
        reports.insert(r);
        audit.record("report", r.getId(), "generate",
                null, Map.of("type", type, "period", label == null ? "" : label, "sha256", sha));
        return meta(r, label);
    }

    public PageResult<ReportDtos.Meta> list(String type, String period, int page, int size) {
        Page<Report> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        reports.selectPage(p, Wrappers.<Report>lambdaQuery()
                .select(Report::getId, Report::getReportType, Report::getPeriodId, Report::getRefId,
                        Report::getPeriodLabel, Report::getCalcVersion, Report::getFileName,
                        Report::getFileSize, Report::getSha256, Report::getGeneratedBy,
                        Report::getGeneratedAt)
                .eq(Report::getOrgId, CurrentUser.orgId())
                .eq(Report::getDisabled, 0)
                .eq(type != null && !type.isBlank(), Report::getReportType, type)
                .eq(period != null && !period.isBlank(), Report::getPeriodLabel, period)
                .orderByDesc(Report::getId));
        List<ReportDtos.Meta> rows = p.getRecords().stream()
                .map(r -> meta(r, r.getPeriodLabel())).toList();
        return new PageResult<>(p.getTotal(), rows, (int) p.getCurrent(), (int) p.getSize());
    }

    /**
     * 单条报告详情（只返回元数据，不返回文件内容）。
     *
     * <p>P1-6：历史实现复用 {@link #download(Long)}（{@code selectById} 全字段），
     * 为了一个元数据响应把整份 PDF BLOB 读进堆内存。这里改为列投影查询。
     */
    public ReportDtos.Meta detail(Long id) {
        Report r = reports.selectOne(Wrappers.<Report>lambdaQuery()
                .select(Report::getId, Report::getOrgId, Report::getReportType, Report::getPeriodId,
                        Report::getRefId, Report::getPeriodLabel, Report::getCalcVersion,
                        Report::getFileName, Report::getFileSize, Report::getSha256,
                        Report::getDisabled, Report::getGeneratedBy, Report::getGeneratedAt)
                .eq(Report::getId, id));
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("报告不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的报告");
        return meta(r, r.getPeriodLabel());
    }

    public Report download(Long id) {
        Report r = reports.selectById(id);
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("报告不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的报告");
        return r;
    }

    public record Bundle(String name, byte[] bytes) {}

    /** 单次打包上限（份）：防止一次请求把整库 PDF 拼成超大 zip 撑爆堆内存。 */
    private static final int BUNDLE_LIMIT = 50;

    /**
     * 按类型/期间打包报告 PDF；文件名带 ID 前缀避免重名。
     *
     * <p>P1-6：打包确实需要 {@code content}（唯一的全字段查询路径），但必须限量——
     * 超过 {@value #BUNDLE_LIMIT} 份直接 400 并提示缩小范围，而不是 OOM。
     */
    public Bundle bundle(String type, String period) {
        List<Report> list = reports.selectList(Wrappers.<Report>lambdaQuery()
                .select(Report::getId, Report::getFileName, Report::getContent)
                .eq(Report::getOrgId, CurrentUser.orgId())
                .eq(Report::getDisabled, 0)
                .eq(type != null && !type.isBlank(), Report::getReportType, type)
                .eq(period != null && !period.isBlank(), Report::getPeriodLabel, period)
                .orderByAsc(Report::getId)
                .last("LIMIT " + (BUNDLE_LIMIT + 1)));
        if (list.isEmpty()) throw BizException.notFound("没有可打包的报告");
        if (list.size() > BUNDLE_LIMIT) {
            throw BizException.validation("单次最多打包 " + BUNDLE_LIMIT + " 份，请缩小类型或期间范围");
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                for (Report r : list) {
                    String entry = r.getId() + "_" + r.getFileName();
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                    zip.write(r.getContent());
                    zip.closeEntry();
                }
            }
            String suffix = (period == null || period.isBlank()) ? "全部" : period;
            return new Bundle("报告打包_" + suffix + ".zip", out.toByteArray());
        } catch (java.io.IOException e) {
            throw BizException.internal("报告打包失败", e.getMessage());
        }
    }

    @Transactional
    public void remove(Long id) {
        // 软删只需要元数据，不必把 PDF 内容读进内存（P1-6 同源问题）
        Report r = reports.selectOne(Wrappers.<Report>lambdaQuery()
                .select(Report::getId, Report::getOrgId, Report::getFileName, Report::getDisabled)
                .eq(Report::getId, id));
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("报告不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的报告");
        reports.update(null, Wrappers.<Report>lambdaUpdate()
                .eq(Report::getId, id).set(Report::getDisabled, 1));
        audit.record("report", id, "delete", Map.of("fileName", r.getFileName()), null);
    }

    private String orgName() {
        String name = jdbc.queryForObject("select name from org where id = ?", String.class, CurrentUser.orgId());
        return name == null ? "" : name;
    }

    private BigDecimal outputValue(String period) {
        BigDecimal v = production.selectList(Wrappers.<ProductionValue>lambdaQuery()
                        .eq(ProductionValue::getOrgId, CurrentUser.orgId())
                        .eq(ProductionValue::getPeriod, period)
                        .eq(ProductionValue::getDisabled, 0))
                .stream().map(ProductionValue::getOutputValue)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (v.signum() > 0) return v.setScale(2, RoundingMode.HALF_UP);
        BigDecimal t = totals.selectList(Wrappers.<TotalValue>lambdaQuery()
                        .eq(TotalValue::getOrgId, CurrentUser.orgId())
                        .eq(TotalValue::getPeriod, period)
                        .eq(TotalValue::getDisabled, 0))
                .stream().map(TotalValue::getOutputValue)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return t.setScale(2, RoundingMode.HALF_UP);
    }

    private void requirePeriod(String period) {
        if (period == null || !period.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            throw BizException.validation("核算期格式为 YYYY-MM");
        }
    }

    /** 只有已提交及之后的状态才允许生成正式报告。 */
    private static boolean submitted(String status) {
        return "submitted".equals(status) || "reviewed".equals(status)
                || "approved".equals(status) || "locked".equals(status);
    }

    private ReportDtos.Meta meta(Report r, String label) {
        return new ReportDtos.Meta(r.getId(), r.getReportType(), label, r.getPeriodId(), r.getRefId(),
                r.getPeriodLabel(), r.getCalcVersion(), r.getFileName(), r.getFileSize(),
                r.getSha256(), r.getGeneratedBy(), r.getGeneratedAt());
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
