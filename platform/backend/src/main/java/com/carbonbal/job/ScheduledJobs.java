package com.carbonbal.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.accounting.AccountingPeriod;
import com.carbonbal.accounting.AccountingPeriodMapper;
import com.carbonbal.alert.AlertRecord;
import com.carbonbal.alert.AlertRecordMapper;
import com.carbonbal.alert.AlertRule;
import com.carbonbal.alert.AlertRuleMapper;
import com.carbonbal.auth.SystemAuth;
import com.carbonbal.common.EnergyMath;
import com.carbonbal.device.Device;
import com.carbonbal.device.DeviceMapper;
import com.carbonbal.device.DeviceReading;
import com.carbonbal.device.DeviceReadingMapper;
import com.carbonbal.org.Org;
import com.carbonbal.org.OrgMapper;
import com.carbonbal.quota.QuotaYear;
import com.carbonbal.quota.QuotaYearMapper;
import com.carbonbal.report.Report;
import com.carbonbal.report.ReportMapper;
import com.carbonbal.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务：预警扫描、配额履约提醒、月报生成。
 * 通过 app.jobs.enabled 控制是否随应用启动；手动触发走 {@code POST /api/jobs/{name}/run}。
 */
@Component
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final OrgMapper orgs;
    private final AlertRuleMapper rules;
    private final AlertRecordMapper records;
    private final AccountingPeriodMapper periods;
    private final DeviceMapper devices;
    private final DeviceReadingMapper readings;
    private final QuotaYearMapper quotas;
    private final ReportMapper reports;
    private final ReportService reportService;

    @Value("${app.jobs.enabled:false}")
    private boolean enabled;

    public ScheduledJobs(OrgMapper orgs, AlertRuleMapper rules, AlertRecordMapper records,
                         AccountingPeriodMapper periods, DeviceMapper devices, DeviceReadingMapper readings,
                         QuotaYearMapper quotas, ReportMapper reports, ReportService reportService) {
        this.orgs = orgs;
        this.rules = rules;
        this.records = records;
        this.periods = periods;
        this.devices = devices;
        this.readings = readings;
        this.quotas = quotas;
        this.reports = reports;
        this.reportService = reportService;
    }

    @Scheduled(cron = "${app.jobs.alert-cron:0 10 * * * *}")
    public void scanAlerts() {
        if (!enabled) return;
        forEachOrg(org -> {
            int n = scanOrg(org);
            if (n > 0) log.info("org {} 预警扫描生成 {} 条记录", org, n);
            return n;
        });
    }

    @Scheduled(cron = "${app.jobs.quota-cron:0 30 8 1 * *}")
    public void quotaReminder() {
        if (!enabled) return;
        forEachOrg(org -> {
            int n = quotaFor(org);
            if (n > 0) log.info("org {} 配额提醒生成 {} 条记录", org, n);
            return n;
        });
    }

    @Scheduled(cron = "${app.jobs.report-cron:0 40 2 1 * *}")
    public void monthlyReports() {
        if (!enabled) return;
        String period = YearMonth.now().minusMonths(1).toString();
        forEachOrg(org -> {
            int n = monthlyFor(org, period);
            if (n > 0) log.info("org {} 上月（{}）月报生成 {} 份", org, period, n);
            return n;
        });
    }

    /* ==================== 可复用的单组织实现 ==================== */

    public int scanOrg(Long orgId) throws Exception {
        return SystemAuth.runAs(orgId, "system", () -> {
            List<AlertRule> list = rules.selectList(Wrappers.<AlertRule>lambdaQuery()
                    .eq(AlertRule::getOrgId, orgId)
                    .eq(AlertRule::getDisabled, 0)
                    .eq(AlertRule::getEnabled, 1));
            if (list.isEmpty()) return 0;
            YearMonth ym = YearMonth.now();
            String period = ym.toString();
            LocalDateTime from = ym.atDay(1).atStartOfDay();
            LocalDateTime to = ym.atEndOfMonth().atTime(23, 59, 59);

            BigDecimal tce = monthTce(orgId, from, to);
            BigDecimal emission = periodTotal(orgId, period);
            int created = 0;
            for (AlertRule r : list) {
                BigDecimal value = r.getMetric() != null && r.getMetric().contains("排放") ? emission
                        : r.getMetric() != null && r.getMetric().contains("能耗") ? tce : null;
                if (value == null || r.getThreshold() == null || !hit(r.getOp(), value, r.getThreshold())) continue;
                Long dup = records.selectCount(Wrappers.<AlertRecord>lambdaQuery()
                        .eq(AlertRecord::getOrgId, orgId)
                        .eq(AlertRecord::getRuleId, r.getId())
                        .eq(AlertRecord::getDisabled, 0)
                        .ge(AlertRecord::getOccurredAt, from));
                if (dup != null && dup > 0) continue;
                records.insert(record(orgId, r.getId(), r.getName(), r.getLevel(), r.getMetric(),
                        value, r.getThreshold(), r.getUnit(), "全厂"));
                created++;
            }
            return created;
        });
    }

    public int quotaFor(Long orgId) throws Exception {
        return SystemAuth.runAs(orgId, "system", () -> {
            int year = LocalDateTime.now().getYear();
            QuotaYear q = quotas.selectOne(Wrappers.<QuotaYear>lambdaQuery()
                    .eq(QuotaYear::getOrgId, orgId).eq(QuotaYear::getDisabled, 0)
                    .eq(QuotaYear::getQuotaYear, year).last("LIMIT 1"));
            if (q == null || q.getTotalQty() == null || q.getTotalQty().signum() <= 0) return 0;
            BigDecimal used = periods.selectList(Wrappers.<AccountingPeriod>lambdaQuery()
                            .eq(AccountingPeriod::getOrgId, orgId))
                    .stream().filter(p -> p.getPeriod() != null && p.getPeriod().startsWith(String.valueOf(year)))
                    .map(p -> p.getTotal() == null ? BigDecimal.ZERO : p.getTotal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ratio = used.multiply(new BigDecimal("100"))
                    .divide(q.getTotalQty(), 2, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("90")) < 0) return 0;
            LocalDateTime from = YearMonth.now().atDay(1).atStartOfDay();
            Long dup = records.selectCount(Wrappers.<AlertRecord>lambdaQuery()
                    .eq(AlertRecord::getOrgId, orgId)
                    .eq(AlertRecord::getTitle, "配额履约提醒")
                    .eq(AlertRecord::getDisabled, 0)
                    .ge(AlertRecord::getOccurredAt, from));
            if (dup != null && dup > 0) return 0;
            records.insert(record(orgId, null, "配额履约提醒", "high", "配额使用率",
                    ratio, new BigDecimal("90"), "%", "全厂"));
            return 1;
        });
    }

    public int monthlyFor(Long orgId, String period) throws Exception {
        return SystemAuth.runAs(orgId, "system", () -> {
            int created = 0;
            AccountingPeriod p = periods.selectOne(Wrappers.<AccountingPeriod>lambdaQuery()
                    .eq(AccountingPeriod::getOrgId, orgId)
                    .eq(AccountingPeriod::getPeriod, period).last("LIMIT 1"));
            if (p != null && p.getCalcVersion() != null && p.getCalcVersion() > 0
                    && countReports(orgId, "carbon", period) == 0) {
                reportService.generate("carbon", period, null);
                created++;
            }
            if (countReports(orgId, "energy", period) == 0 && hasReadings(orgId, period)) {
                reportService.generate("energy", period, null);
                created++;
            }
            return created;
        });
    }

    /* ==================== 内部工具 ==================== */

    private int countReports(Long orgId, String type, String period) {
        Long n = reports.selectCount(Wrappers.<Report>lambdaQuery()
                .eq(Report::getOrgId, orgId)
                .eq(Report::getReportType, type)
                .eq(Report::getPeriodLabel, period)
                .eq(Report::getDisabled, 0));
        return n == null ? 0 : n.intValue();
    }

    private boolean hasReadings(Long orgId, String period) {
        YearMonth ym = YearMonth.parse(period);
        Long n = readings.selectCount(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, orgId)
                .ge(DeviceReading::getTs, ym.atDay(1).atStartOfDay())
                .le(DeviceReading::getTs, ym.atEndOfMonth().atTime(23, 59, 59)));
        return n != null && n > 0;
    }

    private BigDecimal monthTce(Long orgId, LocalDateTime from, LocalDateTime to) {
        Map<Long, Device> devs = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, orgId))
                .forEach(d -> devs.put(d.getId(), d));
        BigDecimal total = BigDecimal.ZERO;
        for (DeviceReading r : readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, orgId)
                .ge(DeviceReading::getTs, from).le(DeviceReading::getTs, to))) {
            Device d = devs.get(r.getDeviceId());
            if (d == null || !EnergyMath.supported(d.getEnergyType())) continue;
            total = total.add(EnergyMath.tce(d.getEnergyType(), EnergyMath.activity(r)));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal periodTotal(Long orgId, String period) {
        AccountingPeriod p = periods.selectOne(Wrappers.<AccountingPeriod>lambdaQuery()
                .eq(AccountingPeriod::getOrgId, orgId)
                .eq(AccountingPeriod::getPeriod, period).last("LIMIT 1"));
        return p == null || p.getTotal() == null ? BigDecimal.ZERO : p.getTotal();
    }

    private boolean hit(String op, BigDecimal value, BigDecimal threshold) {
        String o = op == null ? "大于" : op;
        int c = value.compareTo(threshold);
        return switch (o) {
            case "大于", ">" -> c > 0;
            case "大于等于", ">=" -> c >= 0;
            case "小于", "<" -> c < 0;
            case "小于等于", "<=" -> c <= 0;
            case "等于", "=" -> c == 0;
            default -> false;
        };
    }

    private AlertRecord record(Long orgId, Long ruleId, String title, String level, String metric,
                               BigDecimal value, BigDecimal threshold, String unit, String deviceCode) {
        AlertRecord a = new AlertRecord();
        a.setOrgId(orgId);
        a.setRuleId(ruleId);
        a.setTitle(title);
        a.setLevel(level == null ? "mid" : switch (level) {
            case "紧急", "重要", "high" -> "high";
            case "一般", "mid" -> "mid";
            default -> "low";
        });
        a.setMetric(metric);
        a.setMetricValue(value);
        a.setThreshold(threshold);
        a.setUnit(unit);
        a.setDeviceCode(deviceCode);
        a.setOccurredAt(LocalDateTime.now());
        a.setStatus("open");
        a.setIsRead(0);
        a.setDisabled(0);
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    private void forEachOrg(OrgAction action) {
        List<Org> list = orgs.selectList(Wrappers.<Org>lambdaQuery());
        for (Org o : list) {
            try {
                action.run(o.getId());
            } catch (Exception e) {
                log.warn("org {} 定时任务失败：{}", o.getId(), e.getMessage());
            }
        }
    }

    private interface OrgAction {
        int run(Long orgId) throws Exception;
    }

    /** 手动触发：返回本次生成条数（当前企业）。 */
    public Map<String, Object> runOnce(String name, Long orgId) throws Exception {
        int n = switch (name) {
            case "scan-alerts" -> scanOrg(orgId);
            case "quota-reminder" -> quotaFor(orgId);
            case "monthly-reports" -> monthlyFor(orgId, YearMonth.now().minusMonths(1).toString());
            default -> throw new IllegalArgumentException("未知任务: " + name);
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", name);
        m.put("created", n);
        m.put("at", LocalDateTime.now().toString());
        return m;
    }

    public static List<String> jobNames() {
        return new ArrayList<>(List.of("scan-alerts", "quota-reminder", "monthly-reports"));
    }
}
