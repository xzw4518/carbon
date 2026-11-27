package com.carbonbal.energy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class EnergyService {
    private final EnergyMgmtMapper mgmtMapper;
    private final RetrofitMapper retrofitMapper;
    private final AuditService audit;

    public EnergyService(EnergyMgmtMapper mgmtMapper, RetrofitMapper retrofitMapper, AuditService audit) {
        this.mgmtMapper = mgmtMapper;
        this.retrofitMapper = retrofitMapper;
        this.audit = audit;
    }

    public PageResult<EnergyMgmt> listMgmt(int page, int size, String keyword) {
        Page<EnergyMgmt> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mgmtMapper.selectPage(p, Wrappers.<EnergyMgmt>lambdaQuery()
                .eq(EnergyMgmt::getOrgId, CurrentUser.orgId())
                .eq(EnergyMgmt::getDisabled, 0)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(EnergyMgmt::getOrgName, keyword).or().like(EnergyMgmt::getDuty, keyword))
                .orderByDesc(EnergyMgmt::getAtDate));
        return PageResult.of(p);
    }

    /** 单条详情：不存在或不属于本企业统一 404。 */
    public EnergyMgmt detailMgmt(Long id) {
        EnergyMgmt m = id == null ? null : mgmtMapper.selectOne(Wrappers.<EnergyMgmt>lambdaQuery()
                .eq(EnergyMgmt::getId, id)
                .eq(EnergyMgmt::getOrgId, CurrentUser.orgId())
                .eq(EnergyMgmt::getDisabled, 0));
        if (m == null) throw BizException.notFound("能源管理记录不存在");
        return m;
    }

    @Transactional
    public EnergyMgmt createMgmt(EnergyDtos.MgmtReq req) {
        // uk_mgmt_year 是 (org_id, at_date)，软删记录仍占位：同日期的软删记录直接复活，
        // 既保留原 id 与审计链，也避免"删了却再也建不回来"
        List<EnergyMgmt> same = mgmtMapper.selectList(Wrappers.<EnergyMgmt>lambdaQuery()
                .eq(EnergyMgmt::getOrgId, CurrentUser.orgId())
                .eq(EnergyMgmt::getAtDate, req.atDate()));
        EnergyMgmt revive = same.stream()
                .filter(x -> Integer.valueOf(1).equals(x.getDisabled()))
                .findFirst().orElse(null);
        if (revive != null) {
            applyMgmt(revive, req);
            revive.setDisabled(0);
            revive.setVersion((revive.getVersion() == null ? 0 : revive.getVersion()) + 1);
            revive.setUpdatedBy(CurrentUser.username());
            revive.setUpdatedAt(LocalDateTime.now());
            mgmtMapper.updateById(revive);
            audit.record("energy_mgmt", revive.getId(), "restore", null, revive);
            return revive;
        }
        if (!same.isEmpty()) throw BizException.duplicate("该填报日期已有记录", req.atDate());

        EnergyMgmt m = new EnergyMgmt();
        m.setOrgId(CurrentUser.orgId());
        applyMgmt(m, req);
        m.setDisabled(0);
        m.setVersion(0);
        m.setCreatedBy(CurrentUser.username());
        m.setUpdatedBy(CurrentUser.username());
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        mgmtMapper.insert(m);
        audit.record("energy_mgmt", m.getId(), "create", null, m);
        return m;
    }

    @Transactional
    public EnergyMgmt updateMgmt(Long id, EnergyDtos.MgmtReq req) {
        EnergyMgmt old = requireMgmt(id);
        int rows = mgmtMapper.update(null, Wrappers.<EnergyMgmt>lambdaUpdate()
                .eq(EnergyMgmt::getId, id)
                .eq(req.version() != null, EnergyMgmt::getVersion, req.version())
                .set(EnergyMgmt::getAtDate, req.atDate())
                .set(EnergyMgmt::getOrgName, req.orgName())
                .set(EnergyMgmt::getDuty, req.duty())
                .set(EnergyMgmt::getTeam, req.team())
                .set(EnergyMgmt::getPost, req.post())
                .set(EnergyMgmt::getCharge, req.charge())
                .set(EnergyMgmt::getFiller, req.filler())
                .set(EnergyMgmt::getContact, req.contact())
                .set(EnergyMgmt::getSystemName, req.systemName())
                .set(EnergyMgmt::getReport, req.report())
                .set(EnergyMgmt::getFiling, req.filing())
                .set(EnergyMgmt::getInstName, req.instName())
                .set(EnergyMgmt::getStat, req.stat())
                .set(EnergyMgmt::getPromo, req.promo())
                .set(EnergyMgmt::getTraining, req.training())
                .set(EnergyMgmt::getSubsidy, req.subsidy())
                .set(EnergyMgmt::getRemark, req.remark())
                .set(EnergyMgmt::getUpdatedBy, CurrentUser.username())
                .set(EnergyMgmt::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            EnergyMgmt latest = mgmtMapper.selectById(id);
            throw BizException.conflict("记录已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        EnergyMgmt now = mgmtMapper.selectById(id);
        audit.record("energy_mgmt", id, "update", old, now);
        return now;
    }

    public PageResult<Retrofit> listRetrofit(int page, int size, Integer year, String keyword) {
        Page<Retrofit> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        retrofitMapper.selectPage(p, Wrappers.<Retrofit>lambdaQuery()
                .eq(Retrofit::getOrgId, CurrentUser.orgId())
                .eq(Retrofit::getDisabled, 0)
                .eq(year != null, Retrofit::getProjectYear, year)
                .like(keyword != null && !keyword.isBlank(), Retrofit::getName, keyword)
                .orderByDesc(Retrofit::getProjectYear)
                .orderByDesc(Retrofit::getId));
        return PageResult.of(p);
    }

    /** 单条详情：不存在或不属于本企业统一 404。 */
    public Retrofit detailRetrofit(Long id) {
        Retrofit r = id == null ? null : retrofitMapper.selectOne(Wrappers.<Retrofit>lambdaQuery()
                .eq(Retrofit::getId, id)
                .eq(Retrofit::getOrgId, CurrentUser.orgId())
                .eq(Retrofit::getDisabled, 0));
        if (r == null) throw BizException.notFound("改造项目不存在");
        return r;
    }

    @Transactional
    public Retrofit createRetrofit(EnergyDtos.RetrofitReq req) {
        Retrofit r = new Retrofit();
        r.setOrgId(CurrentUser.orgId());
        applyRetrofit(r, req);
        r.setDisabled(0);
        r.setVersion(0);
        r.setCreatedBy(CurrentUser.username());
        r.setUpdatedBy(CurrentUser.username());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        retrofitMapper.insert(r);
        audit.record("retrofit", r.getId(), "create", null, r);
        return r;
    }

    @Transactional
    public Retrofit updateRetrofit(Long id, EnergyDtos.RetrofitReq req) {
        Retrofit old = requireRetrofit(id);
        int rows = retrofitMapper.update(null, Wrappers.<Retrofit>lambdaUpdate()
                .eq(Retrofit::getId, id)
                .eq(req.version() != null, Retrofit::getVersion, req.version())
                .set(Retrofit::getProjectYear, req.projectYear())
                .set(Retrofit::getName, req.name())
                .set(Retrofit::getRemark, req.remark())
                .set(Retrofit::getInvest, req.invest())
                .set(Retrofit::getInvestUnit, req.investUnit())
                .set(Retrofit::getSaving, req.saving())
                .set(Retrofit::getSavingUnit, req.savingUnit())
                .set(Retrofit::getContract, req.contract())
                .set(Retrofit::getDone, req.done() == null ? "否" : req.done())
                .set(Retrofit::getAtDate, req.atDate())
                .set(Retrofit::getUpdatedBy, CurrentUser.username())
                .set(Retrofit::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Retrofit latest = retrofitMapper.selectById(id);
            throw BizException.conflict("项目已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        Retrofit now = retrofitMapper.selectById(id);
        audit.record("retrofit", id, "update", old, now);
        return now;
    }

    @Transactional
    public void removeMgmt(Long id) {
        EnergyMgmt old = requireMgmt(id);
        mgmtMapper.update(null, Wrappers.<EnergyMgmt>lambdaUpdate()
                .eq(EnergyMgmt::getId, id).set(EnergyMgmt::getDisabled, 1)
                .setSql("version = version + 1"));
        audit.record("energy_mgmt", id, "delete", old, null);
    }

    @Transactional
    public void removeRetrofit(Long id) {
        Retrofit old = requireRetrofit(id);
        retrofitMapper.update(null, Wrappers.<Retrofit>lambdaUpdate()
                .eq(Retrofit::getId, id).set(Retrofit::getDisabled, 1)
                .setSql("version = version + 1"));
        audit.record("retrofit", id, "delete", old, null);
    }

    private EnergyMgmt requireMgmt(Long id) {
        EnergyMgmt m = mgmtMapper.selectById(id);
        if (m == null || Integer.valueOf(1).equals(m.getDisabled())) throw BizException.notFound("记录不存在");
        if (!m.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return m;
    }

    private Retrofit requireRetrofit(Long id) {
        Retrofit r = retrofitMapper.selectById(id);
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("项目不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return r;
    }

    private void applyMgmt(EnergyMgmt m, EnergyDtos.MgmtReq q) {
        m.setAtDate(q.atDate());
        m.setOrgName(q.orgName());
        m.setDuty(q.duty());
        m.setTeam(q.team());
        m.setPost(q.post());
        m.setCharge(q.charge());
        m.setFiller(q.filler());
        m.setContact(q.contact());
        m.setSystemName(q.systemName());
        m.setReport(q.report());
        m.setFiling(q.filing());
        m.setInstName(q.instName());
        m.setStat(q.stat());
        m.setPromo(q.promo());
        m.setTraining(q.training());
        m.setSubsidy(q.subsidy());
        m.setRemark(q.remark());
    }

    private void applyRetrofit(Retrofit r, EnergyDtos.RetrofitReq q) {
        r.setProjectYear(q.projectYear());
        r.setName(q.name());

        r.setRemark(q.remark());
        r.setInvest(q.invest());
        r.setInvestUnit(q.investUnit());
        r.setSaving(q.saving());
        r.setSavingUnit(q.savingUnit());
        r.setContract(q.contract());
        r.setDone(q.done() == null ? "否" : q.done());
        r.setAtDate(q.atDate());
    }
}
