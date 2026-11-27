package com.carbonbal.accounting;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.device.Device;
import com.carbonbal.device.DeviceMapper;
import com.carbonbal.device.DeviceReading;
import com.carbonbal.device.DeviceReadingMapper;
import com.carbonbal.factor.EmissionFactor;
import com.carbonbal.factor.FactorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class AccountingService {
    /** 参与核算的能源品种；水与压缩空气不计碳。 */
    private static final Set<String> CALC_FUELS = Set.of("外购电力", "天然气", "柴油", "汽油", "外购蒸汽");
    private static final Set<String> SCOPE2_FUELS = Set.of("外购电力", "外购蒸汽");
    /** 核算期格式：YYYY-MM。 */
    private static final java.util.regex.Pattern PERIOD_PATTERN =
            java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final AccountingPeriodMapper periods;
    private final AccountingLineMapper lines;
    private final PeriodUnlockMapper unlocks;
    private final DeviceReadingMapper readings;
    private final DeviceMapper devices;
    private final FactorService factors;
    private final AuditService audit;

    public AccountingService(AccountingPeriodMapper periods, AccountingLineMapper lines,
                             PeriodUnlockMapper unlocks, DeviceReadingMapper readings,
                             DeviceMapper devices, FactorService factors, AuditService audit) {
        this.periods = periods;
        this.lines = lines;
        this.unlocks = unlocks;
        this.readings = readings;
        this.devices = devices;
        this.factors = factors;
        this.audit = audit;
    }

    public AccountingDtos.PeriodView get(String period) {
        AccountingPeriod p = find(period);
        if (p == null) {
            return new AccountingDtos.PeriodView(null, period, "absent", null, null, null,
                    0, 0, null, null, null, null, null, null, null, null, List.of(), List.of(), false);
        }
        return view(p);
    }

    @Transactional
    public AccountingDtos.PeriodView calculate(String period) {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw BizException.validation("核算期格式为 YYYY-MM");
        }
        // 先取行锁（当前读）：并发重算串行化，且能拿到其他事务刚提交的最新版本
        AccountingPeriod p = periods.lockForUpdate(CurrentUser.orgId(), period);
        if (p == null) {
            periods.ensureExists(CurrentUser.orgId(), period);
            p = periods.lockForUpdate(CurrentUser.orgId(), period);
        }
        if (p == null) throw BizException.notFound("核算期不存在: " + period);
        if ("locked".equals(p.getStatus())) {
            throw BizException.locked("核算期 " + period + " 已锁定，不能重算");
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
                .eq(DeviceReading::getStatus, "正常")
                .orderByAsc(DeviceReading::getTs));

        int newVersion = (p.getCalcVersion() == null ? 0 : p.getCalcVersion()) + 1;
        BigDecimal s1 = BigDecimal.ZERO;
        BigDecimal s2 = BigDecimal.ZERO;
        List<EmissionFactor> used = new ArrayList<>();
        List<AccountingLine> newLines = new ArrayList<>();
        Map<Long, Device> devMap = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                .forEach(d -> devMap.put(d.getId(), d));

        for (DeviceReading r : rs) {
            Device d = devMap.get(r.getDeviceId());
            if (d == null || !CALC_FUELS.contains(d.getEnergyType())) continue;
            EmissionFactor f = factors.pick(d.getEnergyType(), start, end)
                    .orElseThrow(() -> BizException.factorMissing(
                            "缺少「" + d.getEnergyType() + "」在 " + period + " 生效的已审核因子"));
            BigDecimal rate = r.getRate() == null ? BigDecimal.ONE : r.getRate();
            BigDecimal activity = r.getQty().multiply(rate).setScale(4, RoundingMode.HALF_UP);
            BigDecimal emission = activity.multiply(f.getFactorValue()).setScale(4, RoundingMode.HALF_UP);
            String scopeType = SCOPE2_FUELS.contains(d.getEnergyType()) ? "scope2" : "scope1";
            if ("scope2".equals(scopeType)) s2 = s2.add(emission);
            else s1 = s1.add(emission);

            AccountingLine line = new AccountingLine();
            line.setPeriodId(p.getId());
            line.setCalcVersion(newVersion);
            line.setReadingId(r.getId());
            line.setDeviceId(d.getId());
            line.setFactorId(f.getId());
            line.setScopeType(scopeType);
            line.setActivity(activity);
            line.setActivityUnit(f.getUnit());
            line.setFactorValue(f.getFactorValue());
            line.setEmission(emission);
            line.setCreatedAt(LocalDateTime.now());
            newLines.add(line);
            if (!used.contains(f)) used.add(f);
        }

        if (newLines.isEmpty()) {
            throw BizException.factorMissing("该期间没有可核算的读数，请先导入设备数据");
        }
        newLines.forEach(lines::insert);

        s1 = s1.setScale(4, RoundingMode.HALF_UP);
        s2 = s2.setScale(4, RoundingMode.HALF_UP);
        BigDecimal total = s1.add(s2).setScale(4, RoundingMode.HALF_UP);

        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, p.getVersion())
                .set(AccountingPeriod::getScope1, s1)
                .set(AccountingPeriod::getScope2, s2)
                .set(AccountingPeriod::getTotal, total)
                .set(AccountingPeriod::getCalcVersion, newVersion)
                .set(AccountingPeriod::getStatus, "calculated")
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }

        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "calculate",
                Map.of("calcVersion", p.getCalcVersion(), "status", p.getStatus()),
                Map.of("calcVersion", newVersion, "total", total, "lines", newLines.size(),
                        "factors", used.stream().map(EmissionFactor::getId).toList()));
        return view(now);
    }

    @Transactional
    public AccountingDtos.PeriodView submit(String period, int version) {
        AccountingPeriod p = require(period);
        if ("locked".equals(p.getStatus())) throw BizException.locked("已锁定，无需提交");
        if (!"calculated".equals(p.getStatus())) {
            throw BizException.validation("请先计算再提交");
        }
        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, "submitted")
                .set(AccountingPeriod::getSubmittedBy, CurrentUser.actor())
                .set(AccountingPeriod::getSubmittedAt, LocalDateTime.now())
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "submit", Map.of("status", p.getStatus()),
                Map.of("status", now.getStatus(), "submittedBy", CurrentUser.actor()));
        return view(now);
    }

    /** 复核：复核人不能是编制人。 */
    @Transactional
    public AccountingDtos.PeriodView review(String period, int version, String remark) {
        AccountingPeriod p = require(period);
        if (!"submitted".equals(p.getStatus())) {
            throw BizException.validation("仅已提交的核算期可以复核");
        }
        if (CurrentUser.actor().equals(p.getSubmittedBy())) {
            throw BizException.sod("复核人不能是编制人（职责分离）");
        }
        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, "reviewed")
                .set(AccountingPeriod::getReviewedBy, CurrentUser.actor())
                .set(AccountingPeriod::getReviewedAt, LocalDateTime.now())
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "review",
                Map.of("status", p.getStatus()), Map.of("status", now.getStatus(), "remark", remark == null ? "" : remark));
        return view(now);
    }

    /** 批准：批准人不能是复核人。 */
    @Transactional
    public AccountingDtos.PeriodView approve(String period, int version, String remark) {
        AccountingPeriod p = require(period);
        if (!"reviewed".equals(p.getStatus())) {
            throw BizException.validation("仅已复核的核算期可以批准");
        }
        if (CurrentUser.actor().equals(p.getReviewedBy())) {
            throw BizException.sod("批准人不能是复核人（职责分离）");
        }
        if (CurrentUser.actor().equals(p.getSubmittedBy())) {
            throw BizException.sod("批准人不能是编制人（职责分离）");
        }
        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, "approved")
                .set(AccountingPeriod::getApprovedBy, CurrentUser.actor())
                .set(AccountingPeriod::getApprovedAt, LocalDateTime.now())
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "approve",
                Map.of("status", p.getStatus()), Map.of("status", now.getStatus(), "remark", remark == null ? "" : remark));
        return view(now);
    }

    @Transactional
    public AccountingDtos.PeriodView lock(String period, int version) {
        AccountingPeriod p = require(period);
        if ("locked".equals(p.getStatus())) throw BizException.locked("已锁定");
        if (!"approved".equals(p.getStatus())) {
            throw BizException.validation("请先完成复核与批准再锁定");
        }
        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, "locked")
                .set(AccountingPeriod::getLockedAt, LocalDateTime.now())
                .set(AccountingPeriod::getLockedBy, CurrentUser.actor())
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "lock", Map.of("status", p.getStatus()),
                Map.of("status", now.getStatus(), "lockedBy", now.getLockedBy()));
        return view(now);
    }

    /**
     * 解锁（P2-1 语义校正）：<b>不再</b>把操作人同时写成审批人。
     * <p>
     * 现状：{@code period_unlock.approved_by} 是 {@code NOT NULL}（V1 建表），且本版本没有"第二审批人"接口
     * （双人解锁属 v1.1 功能新增），因此这里把 {@code approved_by} 置空串表示"无独立审批人"，
     * 并在 {@code view()} 输出 {@code selfApproved=true} 如实反映"单人解锁"，审计也写明这一点，
     * 而不是伪造一条"某人已批准"的记录。
     */
    @Transactional
    public AccountingDtos.PeriodView unlock(String period, int version, String reason) {
        AccountingPeriod p = require(period);
        if (!"locked".equals(p.getStatus())) {
            throw BizException.validation("仅已锁定的核算期可以解锁");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.validation("解锁原因必填");
        }
        String actor = CurrentUser.actor();
        PeriodUnlock u = new PeriodUnlock();
        u.setPeriodId(p.getId());
        u.setReason(reason);
        u.setOrgId(CurrentUser.orgId());
        u.setRequestedBy(actor);
        // 空串 = 未审批（列不允许 NULL，见方法注释）；不再写 null 触发约束失败
        u.setApprovedBy("");
        u.setUnlockedAt(LocalDateTime.now());
        unlocks.insert(u);

        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, "draft")
                .set(AccountingPeriod::getLockedAt, null)
                .set(AccountingPeriod::getLockedBy, null)
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
        AccountingPeriod now = periods.selectById(p.getId());
        audit.record("accounting_period", p.getId(), "unlock",
                Map.of("status", p.getStatus()),
                Map.of("status", now.getStatus(), "reason", reason,
                        "requestedBy", actor, "selfApproved", true,
                        "note", "单人解锁：无独立审批人，等待 v1.1 双人解锁流程"));
        return view(now);
    }

    private void transact(AccountingPeriod p, int version, String status) {
        int rows = periods.update(null, Wrappers.<AccountingPeriod>lambdaUpdate()
                .eq(AccountingPeriod::getId, p.getId())
                .eq(AccountingPeriod::getVersion, version)
                .set(AccountingPeriod::getStatus, status)
                .set(AccountingPeriod::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            AccountingPeriod latest = periods.selectById(p.getId());
            throw BizException.conflict("核算期已被他人更新，请刷新后重试",
                    Map.of("currentVersion", latest == null ? p.getVersion() : latest.getVersion()));
        }
    }

    private AccountingPeriod find(String period) {
        return periods.selectOne(Wrappers.<AccountingPeriod>lambdaQuery()
                .eq(AccountingPeriod::getOrgId, CurrentUser.orgId())
                .eq(AccountingPeriod::getPeriod, period));
    }

    private AccountingPeriod require(String period) {
        AccountingPeriod p = find(period);
        if (p == null) throw BizException.notFound("核算期不存在: " + period);
        return p;
    }

    private AccountingDtos.PeriodView view(AccountingPeriod p) {
        int cv = p.getCalcVersion() == null ? 0 : p.getCalcVersion();
        List<AccountingLine> ls = cv == 0 ? List.of()
                : lines.selectList(Wrappers.<AccountingLine>lambdaQuery()
                    .eq(AccountingLine::getPeriodId, p.getId())
                    .eq(AccountingLine::getCalcVersion, cv)
                    .orderByAsc(AccountingLine::getId));
        Map<Long, Device> devMap = new HashMap<>();
        if (!ls.isEmpty()) {
            devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                    .forEach(d -> devMap.put(d.getId(), d));
        }
        Map<Long, EmissionFactor> fmap = new HashMap<>();
        if (!ls.isEmpty()) {
            List<Long> ids = ls.stream().map(AccountingLine::getFactorId).distinct().toList();
            factorsByIds(ids).forEach(f -> fmap.put(f.getId(), f));
        }
        List<AccountingDtos.LineView> lv = ls.stream().map(l -> {
            Device d = devMap.get(l.getDeviceId());
            EmissionFactor f = fmap.get(l.getFactorId());
            return new AccountingDtos.LineView(l.getId(), l.getReadingId(), l.getDeviceId(),
                    d == null ? "" : d.getName(), l.getFactorId(),
                    f == null ? "" : f.getVersion(), l.getScopeType(),
                    l.getActivity(), l.getActivityUnit(), l.getFactorValue(), l.getEmission());
        }).toList();
        List<AccountingDtos.UnlockView> uv = unlocks.selectList(Wrappers.<PeriodUnlock>lambdaQuery()
                        .eq(PeriodUnlock::getPeriodId, p.getId()).orderByDesc(PeriodUnlock::getId))
                .stream().map(u -> new AccountingDtos.UnlockView(u.getId(), u.getReason(),
                        u.getRequestedBy(), blankToNull(u.getApprovedBy()), u.getUnlockedAt())).toList();
        // 无独立审批人（approved_by 为空）即视为单人解锁（P2-1）
        boolean selfApproved = uv.stream().anyMatch(x -> x.approvedBy() == null
                || x.approvedBy().isBlank()
                || x.approvedBy().equals(x.requestedBy()));
        return new AccountingDtos.PeriodView(p.getId(), p.getPeriod(), p.getStatus(),
                p.getScope1(), p.getScope2(), p.getTotal(), cv, p.getVersion(),
                p.getLockedAt(), p.getLockedBy(),
                p.getSubmittedBy(), p.getSubmittedAt(), p.getReviewedBy(), p.getReviewedAt(),
                p.getApprovedBy(), p.getApprovedAt(), lv, uv, selfApproved);
    }

    /** 空串/空白统一转成 null，避免前端把 "" 渲染成"已审批"。 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private List<EmissionFactor> factorsByIds(List<Long> ids) {
        return factors.list(null, null, null).stream().filter(f -> ids.contains(f.getId())).toList();
    }
}
