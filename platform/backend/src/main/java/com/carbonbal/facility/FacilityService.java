package com.carbonbal.facility;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class FacilityService {
    private final FacilityMapper mapper;
    private final AuditService audit;

    public FacilityService(FacilityMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<FacilityDtos.View> list(int page, int size, String keyword, Long parentId) {
        Page<Facility> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, CurrentUser.orgId())
                .eq(Facility::getDisabled, 0)
                .eq(parentId != null, Facility::getParentId, parentId)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Facility::getName, keyword).or().like(Facility::getCode, keyword))
                .orderByAsc(Facility::getTreeLevel)
                .orderByAsc(Facility::getSortNo)
                .orderByAsc(Facility::getId));

        Map<Long, String> parentNames = new HashMap<>();
        List<Long> parentIds = p.getRecords().stream().map(Facility::getParentId)
                .filter(Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) {
            mapper.selectBatchIds(parentIds).forEach(f -> parentNames.put(f.getId(), f.getName()));
        }
        List<FacilityDtos.View> rows = p.getRecords().stream()
                .map(f -> view(f, parentNames.get(f.getParentId())))
                .toList();
        return new PageResult<>(p.getTotal(), rows, (int) p.getCurrent(), (int) p.getSize());
    }

    public FacilityDtos.View get(Long id) {
        Facility f = require(id);
        Facility parent = f.getParentId() == null ? null : mapper.selectById(f.getParentId());
        return view(f, parent == null ? null : parent.getName());
    }

    @Transactional
    public FacilityDtos.View create(FacilityDtos.Req req) {
        assertCodeUnique(req.code(), null);
        int level = 1;
        if (req.parentId() != null) {
            Facility parent = require(req.parentId());
            level = (parent.getTreeLevel() == null ? 1 : parent.getTreeLevel()) + 1;
        }
        Facility f = new Facility();
        f.setOrgId(CurrentUser.orgId());
        f.setName(req.name());
        f.setCode(req.code());
        f.setParentId(req.parentId());
        f.setTreeLevel(level);
        f.setSortNo(req.sort() == null ? 1 : req.sort());
        f.setRemark(req.remark());
        f.setDisabled(0);
        f.setVersion(0);
        f.setCreatedBy(CurrentUser.username());
        f.setUpdatedBy(CurrentUser.username());
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        mapper.insert(f);
        audit.record("facility", f.getId(), "create", null, f);
        return get(f.getId());
    }

    @Transactional
    public FacilityDtos.View update(Long id, FacilityDtos.Req req) {
        Facility old = require(id);
        assertCodeUnique(req.code(), id);
        assertNoCycle(id, req.parentId());

        int level = 1;
        if (req.parentId() != null) {
            Facility parent = require(req.parentId());
            level = (parent.getTreeLevel() == null ? 1 : parent.getTreeLevel()) + 1;
        }

        int rows = mapper.update(null, Wrappers.<Facility>lambdaUpdate()
                .eq(Facility::getId, id)
                .eq(req.version() != null, Facility::getVersion, req.version())
                .set(Facility::getName, req.name())
                .set(Facility::getCode, req.code())
                .set(Facility::getParentId, req.parentId())
                .set(Facility::getTreeLevel, level)
                .set(Facility::getSortNo, req.sort() == null ? 1 : req.sort())
                .set(Facility::getRemark, req.remark())
                .set(Facility::getUpdatedBy, CurrentUser.username())
                .set(Facility::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Facility latest = mapper.selectById(id);
            throw BizException.conflict("设施已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        if (!Objects.equals(old.getTreeLevel(), level) || !Objects.equals(old.getParentId(), req.parentId())) {
            renumberChildren(id, level);
        }
        Facility now = mapper.selectById(id);
        audit.record("facility", id, "update", old, now);
        return get(id);
    }

    @Transactional
    public void remove(Long id) {
        Facility old = require(id);
        Long children = mapper.selectCount(Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, CurrentUser.orgId())
                .eq(Facility::getParentId, id)
                .eq(Facility::getDisabled, 0));
        if (children != null && children > 0) {
            throw BizException.referenced("该设施存在下级设施，请先删除下级", children);
        }
        mapper.update(null, Wrappers.<Facility>lambdaUpdate()
                .eq(Facility::getId, id)
                .set(Facility::getDisabled, 1)
                .set(Facility::getUpdatedBy, CurrentUser.username())
                .set(Facility::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("facility", id, "delete", old, null);
    }

    private Facility require(Long id) {
        Facility f = mapper.selectById(id);
        if (f == null || Integer.valueOf(1).equals(f.getDisabled())) throw BizException.notFound("设施不存在");
        if (!f.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return f;
    }

    private void assertCodeUnique(String code, Long excludeId) {
        Long dup = mapper.selectCount(Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, CurrentUser.orgId())
                .eq(Facility::getCode, code)
                .eq(Facility::getDisabled, 0)
                .ne(excludeId != null, Facility::getId, excludeId));
        if (dup != null && dup > 0) throw BizException.duplicate("设施编码已存在", code);
    }

    private void assertNoCycle(Long id, Long newParentId) {
        Long cursor = newParentId;
        int guard = 0;
        while (cursor != null && guard++ < 50) {
            if (cursor.equals(id)) throw BizException.validation("上级设施不能是自身或下级设施");
            Facility p = mapper.selectById(cursor);
            if (p == null) break;
            cursor = p.getParentId();
        }
    }

    private void renumberChildren(Long parentId, int parentLevel) {
        List<Facility> children = mapper.selectList(Wrappers.<Facility>lambdaQuery()
                .eq(Facility::getOrgId, CurrentUser.orgId())
                .eq(Facility::getParentId, parentId)
                .eq(Facility::getDisabled, 0));
        for (Facility c : children) {
            int level = parentLevel + 1;
            if (!Objects.equals(c.getTreeLevel(), level)) {
                mapper.update(null, Wrappers.<Facility>lambdaUpdate()
                        .eq(Facility::getId, c.getId())
                        .set(Facility::getTreeLevel, level)
                        .setSql("version = version + 1"));
            }
            renumberChildren(c.getId(), level);
        }
    }

    private FacilityDtos.View view(Facility f, String parentName) {
        return new FacilityDtos.View(f.getId(), f.getName(), f.getCode(), f.getParentId(), parentName,
                f.getTreeLevel(), f.getSortNo(), f.getRemark(), f.getVersion());
    }
}
