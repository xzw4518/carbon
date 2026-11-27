package com.carbonbal.evidence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import com.carbonbal.file.FileObject;
import com.carbonbal.file.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EvidenceService {
    private static final String BIZ_EVIDENCE = "evidence";
    private static final String BIZ_THIRD = "third_party_report";

    private final EvidenceMapper evidences;
    private final ThirdPartyReportMapper thirdReports;
    private final FileService files;
    private final AuditService audit;

    public EvidenceService(EvidenceMapper evidences, ThirdPartyReportMapper thirdReports,
                           FileService files, AuditService audit) {
        this.evidences = evidences;
        this.thirdReports = thirdReports;
        this.files = files;
        this.audit = audit;
    }

    /* ---------- 原始凭证 ---------- */

    public PageResult<EvidenceDtos.EvidenceView> listEvidences(int page, int size, String keyword,
                                                               LocalDate from, LocalDate to, String validState) {
        Page<Evidence> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        LocalDate today = LocalDate.now();
        evidences.selectPage(p, Wrappers.<Evidence>lambdaQuery()
                .eq(Evidence::getOrgId, CurrentUser.orgId())
                .eq(Evidence::getDisabled, 0)
                .ge(from != null, Evidence::getAtDate, from)
                .le(to != null, Evidence::getAtDate, to)
                .like(keyword != null && !keyword.isBlank(), Evidence::getName, keyword)
                .lt("expired".equals(validState), Evidence::getValidTo, today)
                .and("valid".equals(validState), w -> w
                        .ge(Evidence::getValidTo, today).or().isNull(Evidence::getValidTo))
                .orderByDesc(Evidence::getAtDate)
                .orderByDesc(Evidence::getId));

        Map<String, List<FileObject>> grouped = files
                .byBizIds(BIZ_EVIDENCE, p.getRecords().stream()
                        .map(e -> String.valueOf(e.getId())).toList())
                .stream().collect(Collectors.groupingBy(FileObject::getBizId));
        List<EvidenceDtos.EvidenceView> rows = p.getRecords().stream()
                .map(e -> evidenceView(e, grouped.getOrDefault(String.valueOf(e.getId()), List.of())))
                .toList();
        return new PageResult<>(p.getTotal(), rows, (int) p.getCurrent(), (int) p.getSize());
    }

    public EvidenceDtos.EvidenceView getEvidence(Long id) {
        Evidence e = requireEvidence(id);
        return evidenceView(e, files.byBizIds(BIZ_EVIDENCE, List.of(String.valueOf(id))));
    }

    @Transactional
    public EvidenceDtos.EvidenceView createEvidence(EvidenceDtos.EvidenceReq req) {
        validate(req.validFrom(), req.validTo());
        assertCodeUnique(req.code(), null);

        Evidence e = new Evidence();
        e.setOrgId(CurrentUser.orgId());
        apply(e, req);
        e.setDisabled(0);
        e.setVersion(0);
        e.setCreatedBy(CurrentUser.username());
        e.setUpdatedBy(CurrentUser.username());
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        evidences.insert(e);
        files.bind(req.fileIds(), BIZ_EVIDENCE, String.valueOf(e.getId()));
        audit.record("evidence", e.getId(), "create", null, e);
        return getEvidence(e.getId());
    }

    @Transactional
    public EvidenceDtos.EvidenceView updateEvidence(Long id, EvidenceDtos.EvidenceReq req) {
        Evidence old = requireEvidence(id);
        validate(req.validFrom(), req.validTo());
        assertCodeUnique(req.code(), id);

        int rows = evidences.update(null, Wrappers.<Evidence>lambdaUpdate()
                .eq(Evidence::getId, id)
                .eq(req.version() != null, Evidence::getVersion, req.version())
                .set(Evidence::getName, req.name())
                .set(Evidence::getRemark, req.remark())
                .set(Evidence::getCode, req.code())
                .set(Evidence::getDescription, req.description())
                .set(Evidence::getValidFrom, req.validFrom())
                .set(Evidence::getValidTo, req.validTo())
                .set(Evidence::getAtDate, req.atDate())
                .set(Evidence::getUpdatedBy, CurrentUser.username())
                .set(Evidence::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Evidence latest = evidences.selectById(id);
            throw BizException.conflict("凭证已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        if (req.fileIds() != null) {
            files.bind(req.fileIds(), BIZ_EVIDENCE, String.valueOf(id));
            files.unbindExcept(BIZ_EVIDENCE, String.valueOf(id), req.fileIds());
        }
        Evidence now = evidences.selectById(id);
        audit.record("evidence", id, "update", old, now);
        return getEvidence(id);
    }

    @Transactional
    public void removeEvidence(Long id) {
        Evidence old = requireEvidence(id);
        evidences.update(null, Wrappers.<Evidence>lambdaUpdate()
                .eq(Evidence::getId, id)
                .set(Evidence::getDisabled, 1)
                .set(Evidence::getUpdatedBy, CurrentUser.username())
                .set(Evidence::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        files.unbindExcept(BIZ_EVIDENCE, String.valueOf(id), List.of());
        audit.record("evidence", id, "delete", old, null);
    }

    /* ---------- 第三方报告 ---------- */

    public PageResult<EvidenceDtos.ThirdReportView> listThirdReports(int page, int size, String keyword,
                                                                     LocalDate from, LocalDate to) {
        Page<ThirdPartyReport> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        thirdReports.selectPage(p, Wrappers.<ThirdPartyReport>lambdaQuery()
                .eq(ThirdPartyReport::getOrgId, CurrentUser.orgId())
                .eq(ThirdPartyReport::getDisabled, 0)
                .ge(from != null, ThirdPartyReport::getAtDate, from)
                .le(to != null, ThirdPartyReport::getAtDate, to)
                .like(keyword != null && !keyword.isBlank(), ThirdPartyReport::getName, keyword)
                .orderByDesc(ThirdPartyReport::getAtDate)
                .orderByDesc(ThirdPartyReport::getId));

        Map<Long, FileObject> fileMap = files.byIds(p.getRecords().stream()
                        .map(ThirdPartyReport::getFileId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(FileObject::getId, Function.identity(), (a, b) -> a));
        List<EvidenceDtos.ThirdReportView> rows = p.getRecords().stream()
                .map(r -> thirdView(r, fileMap.get(r.getFileId())))
                .toList();
        return new PageResult<>(p.getTotal(), rows, (int) p.getCurrent(), (int) p.getSize());
    }

    public EvidenceDtos.ThirdReportView getThirdReport(Long id) {
        ThirdPartyReport r = requireThird(id);
        FileObject f = r.getFileId() == null ? null : safeFile(r.getFileId());
        return thirdView(r, f);
    }

    @Transactional
    public EvidenceDtos.ThirdReportView createThirdReport(EvidenceDtos.ThirdReportReq req) {
        validate(req.validFrom(), req.validTo());
        ThirdPartyReport r = new ThirdPartyReport();
        r.setOrgId(CurrentUser.orgId());
        apply(r, req);
        r.setDisabled(0);
        r.setVersion(0);
        r.setCreatedBy(CurrentUser.username());
        r.setUpdatedBy(CurrentUser.username());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        thirdReports.insert(r);
        if (req.fileId() != null) files.bind(List.of(req.fileId()), BIZ_THIRD, String.valueOf(r.getId()));
        audit.record("third_party_report", r.getId(), "create", null, r);
        return getThirdReport(r.getId());
    }

    @Transactional
    public EvidenceDtos.ThirdReportView updateThirdReport(Long id, EvidenceDtos.ThirdReportReq req) {
        ThirdPartyReport old = requireThird(id);
        validate(req.validFrom(), req.validTo());

        int rows = thirdReports.update(null, Wrappers.<ThirdPartyReport>lambdaUpdate()
                .eq(ThirdPartyReport::getId, id)
                .eq(req.version() != null, ThirdPartyReport::getVersion, req.version())
                .set(ThirdPartyReport::getName, req.name())
                .set(ThirdPartyReport::getRemark, req.remark())
                .set(ThirdPartyReport::getDescription, req.description())
                .set(ThirdPartyReport::getValidFrom, req.validFrom())
                .set(ThirdPartyReport::getValidTo, req.validTo())
                .set(ThirdPartyReport::getAtDate, req.atDate())
                .set(ThirdPartyReport::getFileId, req.fileId())
                .set(ThirdPartyReport::getUpdatedBy, CurrentUser.username())
                .set(ThirdPartyReport::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            ThirdPartyReport latest = thirdReports.selectById(id);
            throw BizException.conflict("第三方报告已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        files.unbindExcept(BIZ_THIRD, String.valueOf(id), req.fileId() == null ? List.of() : List.of(req.fileId()));
        if (req.fileId() != null) files.bind(List.of(req.fileId()), BIZ_THIRD, String.valueOf(id));
        ThirdPartyReport now = thirdReports.selectById(id);
        audit.record("third_party_report", id, "update", old, now);
        return getThirdReport(id);
    }

    @Transactional
    public void removeThirdReport(Long id) {
        ThirdPartyReport old = requireThird(id);
        thirdReports.update(null, Wrappers.<ThirdPartyReport>lambdaUpdate()
                .eq(ThirdPartyReport::getId, id)
                .set(ThirdPartyReport::getDisabled, 1)
                .set(ThirdPartyReport::getUpdatedBy, CurrentUser.username())
                .set(ThirdPartyReport::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        files.unbindExcept(BIZ_THIRD, String.valueOf(id), List.of());
        audit.record("third_party_report", id, "delete", old, null);
    }

    /* ---------- 内部 ---------- */

    private Evidence requireEvidence(Long id) {
        Evidence e = evidences.selectById(id);
        if (e == null || Integer.valueOf(1).equals(e.getDisabled())) throw BizException.notFound("凭证不存在");
        if (!e.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return e;
    }

    private ThirdPartyReport requireThird(Long id) {
        ThirdPartyReport r = thirdReports.selectById(id);
        if (r == null || Integer.valueOf(1).equals(r.getDisabled())) throw BizException.notFound("第三方报告不存在");
        if (!r.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return r;
    }

    private FileObject safeFile(Long id) {
        try {
            return files.meta(id);
        } catch (BizException e) {
            return null;
        }
    }

    private void validate(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) throw BizException.validation("有效期结束日期不能早于开始日期");
    }

    private void assertCodeUnique(String code, Long excludeId) {
        if (code == null || code.isBlank()) return;
        Long dup = evidences.selectCount(Wrappers.<Evidence>lambdaQuery()
                .eq(Evidence::getOrgId, CurrentUser.orgId())
                .eq(Evidence::getCode, code)
                .eq(Evidence::getDisabled, 0)
                .ne(excludeId != null, Evidence::getId, excludeId));
        if (dup != null && dup > 0) throw BizException.duplicate("凭证编号已存在", code);
    }

    private void apply(Evidence e, EvidenceDtos.EvidenceReq r) {
        e.setName(r.name());

        e.setRemark(r.remark());
        e.setCode(r.code());
        e.setDescription(r.description());
        e.setValidFrom(r.validFrom());
        e.setValidTo(r.validTo());
        e.setAtDate(r.atDate());
    }

    private void apply(ThirdPartyReport r, EvidenceDtos.ThirdReportReq q) {
        r.setName(q.name());

        r.setRemark(q.remark());
        r.setDescription(q.description());
        r.setFileId(q.fileId());
        r.setValidFrom(q.validFrom());
        r.setValidTo(q.validTo());
        r.setAtDate(q.atDate());
    }

    private EvidenceDtos.EvidenceView evidenceView(Evidence e, List<FileObject> fs) {
        return new EvidenceDtos.EvidenceView(e.getId(), e.getName(), e.getCode(), e.getDescription(),
                e.getValidFrom(), e.getValidTo(), e.getAtDate(), e.getVersion(),
                fs.stream().map(this::meta).toList(), e.getRemark());
    }

    private EvidenceDtos.ThirdReportView thirdView(ThirdPartyReport r, FileObject f) {
        return new EvidenceDtos.ThirdReportView(r.getId(), r.getName(), r.getDescription(),
                r.getValidFrom(), r.getValidTo(), r.getAtDate(), r.getVersion(), f == null ? null : meta(f), r.getRemark());
    }

    private EvidenceDtos.FileMeta meta(FileObject f) {
        return new EvidenceDtos.FileMeta(f.getId(), f.getName(), f.getExt(), f.getSize(), f.getSha256());
    }
}
