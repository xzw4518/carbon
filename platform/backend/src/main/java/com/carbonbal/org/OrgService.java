package com.carbonbal.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.file.FileObject;
import com.carbonbal.file.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OrgService {
    private final OrgMapper orgs;
    private final FileService files;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public OrgService(OrgMapper orgs, FileService files, AuditService audit,
                      org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.files = files;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public OrgDtos.OrgView get() {
        return view(require());
    }

    /** 租户管理员可见的授权信息：只读，续期由平台操作。 */
    public Map<String, Object> license() {
        Org o = require();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        String status = o.getStatus() == null ? "active" : o.getStatus();
        if ("disabled".equals(status)) status = "suspended";
        if ("active".equals(status) && o.getExpireAt() != null
                && o.getExpireAt().isBefore(LocalDateTime.now())) status = "expired";
        m.put("orgId", o.getId());
        m.put("name", o.getName());
        m.put("code", o.getCode());
        m.put("plan", o.getPlan());
        m.put("status", status);
        m.put("licenseStartAt", o.getLicenseStartAt() == null ? null : o.getLicenseStartAt().toString());
        m.put("licenseEndAt", o.getExpireAt() == null ? null : o.getExpireAt().toLocalDate().toString());
        m.put("remainDays", o.getExpireAt() == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), o.getExpireAt().toLocalDate()));
        m.put("seatLimit", o.getSeatLimit());
        Integer used = jdbc.queryForObject(
                "select count(*) from user_tenant where org_id = ? and status <> 'removed'", Integer.class, o.getId());
        m.put("seatUsed", used == null ? 0 : used);
        return m;
    }

    @Transactional
    public OrgDtos.OrgView update(OrgDtos.OrgReq req) {
        Org old = require();
        Long dup = orgs.selectCount(Wrappers.<Org>lambdaQuery()
                .eq(Org::getCode, req.code())
                .ne(Org::getId, old.getId()));
        if (dup != null && dup > 0) throw BizException.duplicate("统一社会信用代码已被其他企业使用", req.code());

        if (req.logoFileId() != null) files.bind(List.of(req.logoFileId()), "org_logo", String.valueOf(old.getId()));
        if (req.licenseFileId() != null) files.bind(List.of(req.licenseFileId()), "org_license", String.valueOf(old.getId()));

        int rows = orgs.update(null, Wrappers.<Org>lambdaUpdate()
                .eq(Org::getId, old.getId())
                .eq(req.version() != null, Org::getVersion, req.version())
                .set(Org::getName, req.name())
                .set(Org::getShortName, req.shortName())
                .set(Org::getOrgType, req.orgType())
                .set(Org::getCode, req.code())
                .set(Org::getIndustry, req.industry())
                .set(Org::getScale, req.scale())
                .set(Org::getRegion, req.region())
                .set(Org::getLegalPerson, req.legalPerson())
                .set(Org::getRegDate, req.regDate())
                .set(Org::getRegCapital, req.regCapital())
                .set(Org::getContact, req.contact())
                .set(Org::getPhone, req.phone())
                .set(Org::getEmail, req.email())
                .set(Org::getIntro, req.intro())
                .set(Org::getRemark, req.remark())
                .set(Org::getLogoFileId, req.logoFileId())
                .set(Org::getLicenseFileId, req.licenseFileId())
                .set(Org::getUpdatedBy, CurrentUser.username())
                .set(Org::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Org latest = orgs.selectById(old.getId());
            throw BizException.conflict("企业信息已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        Org now = orgs.selectById(old.getId());
        audit.record("org", old.getId(), "update", old, now);
        return view(now);
    }

    private Org require() {
        Org org = orgs.selectById(CurrentUser.orgId());
        if (org == null) throw BizException.notFound("企业不存在");
        return org;
    }

    private OrgDtos.OrgView view(Org o) {
        return new OrgDtos.OrgView(o.getId(), o.getName(), o.getShortName(), o.getOrgType(), o.getCode(),
                o.getIndustry(), o.getScale(), o.getRegion(), o.getLegalPerson(), o.getRegDate(),
                o.getRegCapital(), o.getContact(), o.getPhone(), o.getEmail(), o.getIntro(), o.getRemark(),
                o.getLogoFileId(), o.getLicenseFileId(), o.getVersion() == null ? 0 : o.getVersion(),
                ref(o.getLogoFileId()), ref(o.getLicenseFileId()));
    }

    private OrgDtos.FileRef ref(Long fileId) {
        if (fileId == null) return null;
        try {
            FileObject f = files.meta(fileId);
            return new OrgDtos.FileRef(f.getId(), f.getName());
        } catch (BizException e) {
            return null;
        }
    }
}
