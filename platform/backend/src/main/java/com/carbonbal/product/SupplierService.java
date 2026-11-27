package com.carbonbal.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PageResult;
import com.carbonbal.common.UniqueKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SupplierService {
    /** 供应链层级上限：一级 ~ 五级 */
    private static final int MAX_DEPTH = 5;

    /** 足迹单位白名单：与前端下拉选项保持一致，避免接口写入无意义单位 */
    private static final Set<String> FP_UNITS = Set.of(
            "tCO2e/t", "kgCO2e/kg", "gCO2e/g", "tCO2e/万Nm³", "tCO2e/Nm³",
            "tCO2e/万个", "kgCO2e/个", "tCO2e/万件", "kgCO2e/件", "tCO2e/万m");
    private static final String[] LEVEL_NAMES = {"一级", "二级", "三级", "四级", "五级"};

    private final SupplierMapper mapper;
    private final AuditService audit;

    public SupplierService(SupplierMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public PageResult<Supplier> list(int page, int size, String keyword, String level) {
        Page<Supplier> p = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        mapper.selectPage(p, Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0)
                .eq(level != null && !level.isBlank(), Supplier::getLevel, level)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Supplier::getName, keyword).or().like(Supplier::getProduct, keyword))
                .orderByAsc(Supplier::getSort)
                .orderByDesc(Supplier::getId));
        return PageResult.of(p);
    }

    /** 全量树：根节点为 parent_id 为空（或父节点已删除）的供应商。 */
    public List<Map<String, Object>> tree() {
        List<Supplier> all = mapper.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0)
                .orderByAsc(Supplier::getSort)
                .orderByAsc(Supplier::getId));
        Map<Long, List<Supplier>> byParent = all.stream()
                .collect(Collectors.groupingBy(s -> s.getParentId() == null ? 0L : s.getParentId(),
                        LinkedHashMap::new, Collectors.toList()));
        Set<Long> ids = all.stream().map(Supplier::getId).collect(Collectors.toSet());
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Supplier s : all) {
            if (s.getParentId() == null || !ids.contains(s.getParentId())) roots.add(node(s, byParent));
        }
        return roots;
    }

    private Map<String, Object> node(Supplier s, Map<Long, List<Supplier>> byParent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("parentId", s.getParentId());
        m.put("name", s.getName());
        m.put("product", s.getProduct());
        m.put("unit", s.getUnit());
        m.put("fpUnit", s.getFpUnit());
        m.put("footprint", s.getFootprint());
        m.put("date", s.getDate());
        m.put("target", s.getTarget());
        m.put("level", s.getLevel());
        m.put("sort", s.getSort());
        m.put("children", byParent.getOrDefault(s.getId(), List.of()).stream()
                .map(c -> node(c, byParent)).toList());
        return m;
    }

    /** 单条详情：不存在或不属于本企业统一 404。 */
    public Supplier detail(Long id) {
        Supplier s = id == null ? null : mapper.selectOne(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getId, id)
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getDisabled, 0));
        if (s == null) throw BizException.notFound("供应商不存在");
        return s;
    }

    @Transactional
    public Supplier create(MasterDataDtos.SupplierReq req) {
        validateParent(null, req.parentId());
        validateTarget(req.target());
        validateFpUnit(req.fpUnit());
        Supplier s = new Supplier();
        s.setOrgId(CurrentUser.orgId());
        apply(s, req);
        s.setParentId(req.parentId());
        s.setLevel(levelName(parentDepth(req.parentId()) + 1));
        s.setDisabled(0);
        s.setVersion(0);
        s.setCreatedBy(CurrentUser.username());
        s.setUpdatedBy(CurrentUser.username());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        mapper.insert(s);
        audit.record("supplier", s.getId(), "create", null, s);
        return s;
    }

    @Transactional
    public Supplier update(Long id, MasterDataDtos.SupplierReq req) {
        Supplier old = require(id);
        validateParent(id, req.parentId());
        validateTarget(req.target());
        validateFpUnit(req.fpUnit());
        int rows = mapper.update(null, Wrappers.<Supplier>lambdaUpdate()
                .eq(Supplier::getId, id)
                .eq(req.version() != null, Supplier::getVersion, req.version())
                .set(Supplier::getParentId, req.parentId())
                .set(Supplier::getName, req.name())
                .set(Supplier::getProduct, req.product())
                .set(Supplier::getUnit, req.unit())
                .set(Supplier::getFpUnit, req.fpUnit())
                .set(Supplier::getFootprint, req.footprint())
                .set(Supplier::getDate, req.date())
                .set(Supplier::getTarget, req.target())
                .set(Supplier::getSort, req.sort() == null ? 0 : req.sort())
                .set(Supplier::getRemark, req.remark())
                .set(Supplier::getUpdatedBy, CurrentUser.username())
                .set(Supplier::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Supplier latest = mapper.selectById(id);
            throw BizException.conflict("供应商已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        // 上级变更后，自身与所有后代的层级都要重算
        recalcLevels(CurrentUser.orgId());
        Supplier now = mapper.selectById(id);
        audit.record("supplier", id, "update", old, now);
        return now;
    }

    @Transactional
    public void remove(Long id) {
        Supplier old = require(id);
        long children = mapper.selectCount(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, CurrentUser.orgId())
                .eq(Supplier::getParentId, id)
                .eq(Supplier::getDisabled, 0));
        if (children > 0) {
            throw BizException.referenced("请先删除或转移下级供应商", Map.of("children", children));
        }
        // 唯一键是 (org_id, name, product)：软删后释放名称，允许同名同产品重建
        mapper.update(null, Wrappers.<Supplier>lambdaUpdate()
                .eq(Supplier::getId, id).set(Supplier::getDisabled, 1)
                .set(Supplier::getName, UniqueKeys.release(old.getName(), id, 120))
                .setSql("version = version + 1"));
        audit.record("supplier", id, "delete", old, null);
    }

    /** 仅调整上级（供批量导入两阶段绑定使用）。 */
    @Transactional
    public void assignParent(Long id, Long parentId) {
        Supplier old = require(id);
        validateParent(id, parentId);
        mapper.update(null, Wrappers.<Supplier>lambdaUpdate()
                .eq(Supplier::getId, id)
                .set(Supplier::getParentId, parentId)
                .set(Supplier::getLevel, levelName(parentDepth(parentId) + 1))
                .set(Supplier::getUpdatedBy, CurrentUser.username())
                .set(Supplier::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        recalcLevels(CurrentUser.orgId());
        audit.record("supplier", id, "update", old, mapper.selectById(id));
    }

    private Supplier require(Long id) {
        Supplier s = mapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getDisabled())) throw BizException.notFound("供应商不存在");
        if (!s.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        return s;
    }

    /** 上级校验：存在、同租户、非自身、不成环、层级不超上限。 */
    private void validateParent(Long id, Long parentId) {
        if (parentId == null) return;
        if (parentId.equals(id)) throw BizException.validation("上级供应商不能是自己");
        Supplier p = mapper.selectById(parentId);
        if (p == null || Integer.valueOf(1).equals(p.getDisabled())) throw BizException.validation("上级供应商不存在");
        if (!p.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的数据");
        if (parentDepth(parentId) + 1 > MAX_DEPTH) {
            throw BizException.validation("供应链层级不能超过 " + MAX_DEPTH + " 级");
        }
        Set<Long> seen = new HashSet<>();
        Long cur = parentId;
        while (cur != null) {
            if (cur.equals(id)) throw BizException.validation("上级供应商不能是自己的下级");
            if (!seen.add(cur)) throw BizException.validation("供应链层级存在循环引用");
            Supplier c = mapper.selectById(cur);
            if (c == null) break;
            cur = c.getParentId();
        }
    }

    /** 指定节点向上的祖先链长度（不含自身）；0 表示它本身是根。 */
    private int parentDepth(Long id) {
        int depth = 0;
        Long cur = id;
        Set<Long> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            Supplier p = mapper.selectById(cur);
            if (p == null) break;
            depth++;
            cur = p.getParentId();
        }
        return depth;
    }

    /** 全量重算层级文本，保证"一级/二级/…"始终与树深度一致。 */
    private void recalcLevels(Long orgId) {
        List<Supplier> all = mapper.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getOrgId, orgId)
                .eq(Supplier::getDisabled, 0));
        Map<Long, Supplier> byId = all.stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
        for (Supplier s : all) {
            int d = 1;
            Set<Long> seen = new HashSet<>();
            Long cur = s.getParentId();
            while (cur != null && seen.add(cur)) {
                Supplier p = byId.get(cur);
                if (p == null) break;
                d++;
                cur = p.getParentId();
            }
            String lv = levelName(d);
            if (!lv.equals(s.getLevel())) {
                mapper.update(null, Wrappers.<Supplier>lambdaUpdate()
                        .eq(Supplier::getId, s.getId())
                        .set(Supplier::getLevel, lv));
            }
        }
    }

    private static String levelName(int depth) {
        return LEVEL_NAMES[Math.min(Math.max(depth, 1), MAX_DEPTH) - 1];
    }

    /** 足迹单位只允许白名单内的写法（与前端下拉一致） */
    private void validateFpUnit(String unit) {
        if (unit == null || unit.isBlank()) return;
        if (!FP_UNITS.contains(unit.trim())) {
            throw BizException.validation("足迹单位不支持：" + unit + "，请从标准单位中选择");
        }
    }

    /** 核算范围只允许 范围一/二/三；留空表示未标注（兼容历史数据） */
    private void validateTarget(String target) {
        if (target == null || target.isBlank()) return;
        if (!Set.of("范围一", "范围二", "范围三").contains(target.trim())) {
            throw BizException.validation("核算范围只能是 范围一/范围二/范围三");
        }
    }

    private void apply(Supplier s, MasterDataDtos.SupplierReq r) {
        s.setName(r.name());
        s.setProduct(r.product());
        s.setUnit(r.unit());
        s.setFpUnit(r.fpUnit());
        s.setFootprint(r.footprint());
        s.setDate(r.date());
        s.setTarget(r.target());
        s.setSort(r.sort() == null ? 0 : r.sort());
        s.setRemark(r.remark());
    }
}
