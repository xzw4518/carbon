package com.carbonbal.device;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.common.UniqueKeys;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.facility.Facility;
import com.carbonbal.facility.FacilityMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeviceService {
    private final DeviceMapper devices;
    private final DeviceReadingMapper readings;
    private final FacilityMapper facilities;
    private final AuditService audit;

    public DeviceService(DeviceMapper devices, DeviceReadingMapper readings,
                         FacilityMapper facilities, AuditService audit) {
        this.devices = devices;
        this.readings = readings;
        this.facilities = facilities;
        this.audit = audit;
    }

    public List<DeviceDtos.DeviceView> list(String keyword, String energyType, Boolean includeDisabled) {
        Map<Long, String> fac = new HashMap<>();
        facilities.selectList(Wrappers.<Facility>lambdaQuery().eq(Facility::getOrgId, CurrentUser.orgId()))
                .forEach(f -> fac.put(f.getId(), f.getName()));
        return devices.selectList(Wrappers.<Device>lambdaQuery()
                        .eq(Device::getOrgId, CurrentUser.orgId())
                        .eq(energyType != null && !energyType.isBlank(), Device::getEnergyType, energyType)
                        .eq(!Boolean.TRUE.equals(includeDisabled), Device::getDisabled, false)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(Device::getName, keyword).or().like(Device::getCode, keyword))
                        .orderByAsc(Device::getCode))
                .stream().map(d -> view(d, fac.get(d.getFacilityId()))).toList();
    }

    @Transactional
    public DeviceDtos.DeviceView create(DeviceDtos.DeviceReq req) {
        requireCodeFree(req.code(), null);
        Device d = new Device();
        d.setOrgId(CurrentUser.orgId());
        d.setName(req.name());
        d.setCode(req.code().trim());
        d.setEnergyType(req.energyType());
        d.setUnit(req.unit() == null ? unitOf(req.energyType()) : req.unit());
        d.setStatus(req.status() == null ? "online" : req.status());
        d.setFacilityId(requireFacility(req.facilityId()));
        d.setDeviceType(req.deviceType());
        d.setLevel(req.level());
        d.setRemark(req.remark());
        d.setVersion(0);
        d.setDisabled(false);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        devices.insert(d);
        audit.record("device", d.getId(), "create", null, d);
        return view(d, facilityName(d.getFacilityId()));
    }

    @Transactional
    public DeviceDtos.DeviceView update(Long id, DeviceDtos.DeviceReq req) {
        Device old = require(id);
        requireCodeFree(req.code(), id);
        int rows = devices.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, id)
                .eq(Device::getOrgId, CurrentUser.orgId())
                .eq(req.version() != null, Device::getVersion, req.version())
                .set(Device::getName, req.name())
                .set(Device::getCode, req.code().trim())
                .set(Device::getEnergyType, req.energyType())
                .set(Device::getUnit, req.unit() == null ? old.getUnit() : req.unit())
                .set(Device::getStatus, req.status() == null ? old.getStatus() : req.status())
                .set(Device::getFacilityId, requireFacility(req.facilityId()))
                .set(Device::getDeviceType, req.deviceType())
                .set(Device::getLevel, req.level())
                .set(Device::getRemark, req.remark())
                .set(Device::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            Device latest = devices.selectById(id);
            throw BizException.conflict("设备已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        Device now = devices.selectById(id);
        audit.record("device", id, "update", old, now);
        return view(now, facilityName(now.getFacilityId()));
    }

    @Transactional
    public void disable(Long id, boolean disabled) {
        Device old = requireAny(id);
        devices.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, id)
                .eq(Device::getOrgId, CurrentUser.orgId())
                .set(Device::getDisabled, disabled)
                .set(Device::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("device", id, disabled ? "disable" : "enable",
                java.util.Map.of("disabled", old.getDisabled() == null ? 0 : old.getDisabled()),
                java.util.Map.of("disabled", disabled ? 1 : 0));
    }

    @Transactional
    public void remove(Long id) {
        Device old = require(id);
        Long n = readings.selectCount(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                .eq(DeviceReading::getDeviceId, id));
        if (n != null && n > 0) {
            throw BizException.referenced("该设备已有 " + n + " 条读数，不能删除；可改为禁用", Map.of("readings", n));
        }
        devices.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, id)
                .eq(Device::getOrgId, CurrentUser.orgId())
                .set(Device::getDisabled, true)
                // 释放编码，否则同编码无法重建（唯一索引不含 disabled）
                .set(Device::getCode, UniqueKeys.release(old.getCode(), id, 64))
                .set(Device::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        audit.record("device", id, "delete", old, null);
    }

    /** CSV 导入：设备编码,设备名称,能源种类,单位,设施编码,设备类型,级别 */
    /** 逐行独立提交，单行失败不影响其他行。 */
    public DeviceDtos.ImportResult importCsv(MultipartFile file) {
        List<Map<String, Object>> errors = new ArrayList<>();
        int ok = 0, failed = 0;
        Map<String, Long> facByCode = new HashMap<>();
        facilities.selectList(Wrappers.<Facility>lambdaQuery().eq(Facility::getOrgId, CurrentUser.orgId()))
                .forEach(f -> { facByCode.put(f.getCode(), f.getId()); facByCode.put(f.getName(), f.getId()); });
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null) line = line.replace("\uFEFF", "");
            int no = 1;
            while ((line = br.readLine()) != null) {
                no++;
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                try {
                    if (c.length < 4) throw new IllegalArgumentException("列数不足，需 4 列以上");
                    String code = c[0].trim();
                    if (code.isEmpty()) throw new IllegalArgumentException("设备编码为空");
                    Long existing = devices.selectCount(Wrappers.<Device>lambdaQuery()
                            .eq(Device::getOrgId, CurrentUser.orgId())
                            .eq(Device::getCode, code).eq(Device::getDisabled, false));
                    if (existing != null && existing > 0) throw new IllegalArgumentException("设备编码已存在: " + code);
                    Long facId = c.length > 4 && !c[4].isBlank() ? facByCode.get(c[4].trim()) : null;
                    if (c.length > 4 && !c[4].isBlank() && facId == null) {
                        throw new IllegalArgumentException("设施编码不存在: " + c[4].trim());
                    }
                    create(new DeviceDtos.DeviceReq(c[1].trim(), code, c[2].trim(),
                            c.length > 3 && !c[3].isBlank() ? c[3].trim() : null, "online", facId,
                            c.length > 5 ? c[5].trim() : null, c.length > 6 ? c[6].trim() : null, null, null));
                    ok++;
                } catch (Exception e) {
                    failed++;
                    errors.add(Map.of("line", no, "reason", e.getMessage() == null ? "解析失败" : e.getMessage()));
                }
            }
        } catch (Exception e) {
            throw BizException.validation("文件解析失败：" + e.getMessage());
        }
        return new DeviceDtos.ImportResult(ok, failed, errors);
    }

    /** 单条详情：不存在、已禁用或跨企业统一 404。 */
    public DeviceDtos.DeviceView detail(Long id) {
        Device d = id == null ? null : devices.selectById(id);
        if (d == null || Boolean.TRUE.equals(d.getDisabled())
                || !CurrentUser.orgId().equals(d.getOrgId())) {
            throw BizException.notFound("设备不存在");
        }
        return view(d, facilityName(d.getFacilityId()));
    }

    private DeviceDtos.DeviceView view(Device d, String facilityName) {
        return new DeviceDtos.DeviceView(d.getId(), d.getName(), d.getCode(), d.getEnergyType(),
                d.getUnit(), d.getStatus(), d.getFacilityId(), facilityName, d.getDeviceType(),
                d.getLevel(), d.getRemark(), d.getVersion(), d.getDisabled());
    }

    private String facilityName(Long id) {
        if (id == null) return null;
        Facility f = facilities.selectById(id);
        return f == null ? null : f.getName();
    }

    private Long requireFacility(Long id) {
        if (id == null) return null;
        Facility f = facilities.selectById(id);
        if (f == null || Integer.valueOf(1).equals(f.getDisabled())
                || !f.getOrgId().equals(CurrentUser.orgId())) {
            throw BizException.validation("设施不存在或不属于当前企业");
        }
        return id;
    }

    private void requireCodeFree(String code, Long selfId) {
        Long n = devices.selectCount(Wrappers.<Device>lambdaQuery()
                .eq(Device::getOrgId, CurrentUser.orgId())
                .eq(Device::getCode, code.trim())
                .eq(Device::getDisabled, false)
                .ne(selfId != null, Device::getId, selfId));
        if (n != null && n > 0) throw BizException.duplicate("设备编码已存在", Map.of("code", code));
    }

    /** 启用/禁用需要能看到已禁用记录 */
    private Device requireAny(Long id) {
        Device d = devices.selectById(id);
        if (d == null) throw BizException.notFound("设备不存在");
        if (!d.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的设备");
        return d;
    }

    private Device require(Long id) {
        Device d = devices.selectById(id);
        if (d == null || Boolean.TRUE.equals(d.getDisabled())) throw BizException.notFound("设备不存在");
        if (!d.getOrgId().equals(CurrentUser.orgId())) throw BizException.forbidden("无权访问其他企业的设备");
        return d;
    }

    private String unitOf(String energyType) {
        return switch (energyType == null ? "" : energyType) {
            case "外购电力" -> "mWh";
            case "天然气" -> "万Nm3";
            case "外购蒸汽" -> "t";
            case "柴油" -> "t";
            default -> "";
        };
    }
}
