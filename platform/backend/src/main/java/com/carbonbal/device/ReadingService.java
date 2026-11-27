package com.carbonbal.device;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import com.carbonbal.common.PeriodGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReadingService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DeviceReadingMapper readings;
    private final DeviceMapper devices;
    private final PeriodGuard guard;
    private final AuditService audit;

    public ReadingService(DeviceReadingMapper readings, DeviceMapper devices, PeriodGuard guard, AuditService audit) {
        this.readings = readings;
        this.devices = devices;
        this.guard = guard;
        this.audit = audit;
    }

    public com.carbonbal.common.PageResult<ReadingDtos.View> page(int page, int size, Long deviceId, String keyword,
                                                                 String status, LocalDateTime from, LocalDateTime to) {
        List<Device> devs = devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()));
        Map<Long, Device> devMap = new HashMap<>();
        devs.forEach(d -> devMap.put(d.getId(), d));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DeviceReading> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200));
        List<Long> nameIds = null;
        if (keyword != null && !keyword.isBlank()) {
            nameIds = devs.stream().filter(d -> d.getName() != null && d.getName().contains(keyword))
                    .map(Device::getId).toList();
            if (nameIds.isEmpty()) return new com.carbonbal.common.PageResult<>(0, List.of(), 1, (int) p.getSize());
        }
        var q = Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                .eq(deviceId != null, DeviceReading::getDeviceId, deviceId)
                .in(nameIds != null, DeviceReading::getDeviceId, nameIds)
                .eq(status != null && !status.isBlank(), DeviceReading::getStatus, status)
                .ge(from != null, DeviceReading::getTs, from)
                .le(to != null, DeviceReading::getTs, to)
                .orderByDesc(DeviceReading::getTs)
                .orderByDesc(DeviceReading::getId);
        readings.selectPage(p, q);
        List<ReadingDtos.View> rows = p.getRecords().stream().map(r -> toView(r, devMap.get(r.getDeviceId()))).toList();
        return new com.carbonbal.common.PageResult<>(p.getTotal(), rows, (int) p.getCurrent(), (int) p.getSize());
    }

    public List<ReadingDtos.View> list(Long deviceId, LocalDateTime from, LocalDateTime to) {
        List<DeviceReading> rows = readings.selectList(Wrappers.<DeviceReading>lambdaQuery()
                .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                .eq(deviceId != null, DeviceReading::getDeviceId, deviceId)
                .ge(from != null, DeviceReading::getTs, from)
                .le(to != null, DeviceReading::getTs, to)
                .orderByDesc(DeviceReading::getTs)
                .orderByDesc(DeviceReading::getId));
        Map<Long, Device> devMap = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                .forEach(d -> devMap.put(d.getId(), d));
        return rows.stream().map(r -> toView(r, devMap.get(r.getDeviceId()))).toList();
    }

    @Transactional
    public ReadingDtos.View create(ReadingDtos.CreateReq req) {
        guard.assertEditable(req.ts().toLocalDate().toString().substring(0, 7));
        if (req.clientRequestId() != null && !req.clientRequestId().isBlank()) {
            DeviceReading dup = readings.selectOne(Wrappers.<DeviceReading>lambdaQuery()
                    .eq(DeviceReading::getOrgId, CurrentUser.orgId())
                    .eq(DeviceReading::getClientRequestId, req.clientRequestId()));
            if (dup != null) return toView(dup, devices.selectById(dup.getDeviceId()));
        }
        Device d = devices.selectById(req.deviceId());
        if (d == null) throw BizException.notFound("设备不存在");

        DeviceReading r = new DeviceReading();
        r.setOrgId(CurrentUser.orgId());
        r.setDeviceId(req.deviceId());
        r.setTs(req.ts());
        r.setQty(req.qty());
        r.setUnit(req.unit());
        r.setRate(req.rate() == null ? BigDecimal.ONE : req.rate());
        r.setSource(req.source());
        r.setStatus(req.status() == null ? "正常" : req.status());
        r.setRemark(req.remark());
        r.setVersion(0);
        r.setClientRequestId(req.clientRequestId());
        r.setCreatedBy(CurrentUser.username());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        readings.insert(r);
        audit.record("device_reading", r.getId(), "create", null, r);
        return toView(r, d);
    }

    @Transactional
    public ReadingDtos.View update(Long id, ReadingDtos.UpdateReq req) {
        DeviceReading old = readings.selectById(id);
        if (old == null) throw BizException.notFound("读数不存在");
        guard.assertEditable(old.getTs().toLocalDate().toString().substring(0, 7));

        int rows = readings.update(null, Wrappers.<DeviceReading>lambdaUpdate()
                .eq(DeviceReading::getId, id)
                .eq(DeviceReading::getVersion, req.version())
                .set(req.qty() != null, DeviceReading::getQty, req.qty())
                .set(req.unit() != null, DeviceReading::getUnit, req.unit())
                .set(req.rate() != null, DeviceReading::getRate, req.rate())
                .set(req.source() != null, DeviceReading::getSource, req.source())
                .set(req.status() != null, DeviceReading::getStatus, req.status())
                .set(req.remark() != null, DeviceReading::getRemark, req.remark())
                .set(DeviceReading::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows == 0) {
            DeviceReading latest = readings.selectById(id);
            throw BizException.conflict("数据已被他人修改，请刷新后重试",
                    Map.of("currentVersion", latest == null ? old.getVersion() : latest.getVersion()));
        }
        DeviceReading now = readings.selectById(id);
        audit.record("device_reading", id, "update", old, now);
        return toView(now, devices.selectById(now.getDeviceId()));
    }

    /** 删除读数：录入错误时的纠错入口，已定稿期间拒绝。 */
    @Transactional
    public void delete(Long id) {
        DeviceReading old = readings.selectById(id);
        if (old == null) throw BizException.notFound("读数不存在");
        guard.assertEditable(old.getTs().toLocalDate().toString().substring(0, 7));
        readings.deleteById(id);
        audit.record("device_reading", id, "delete", old, null);
    }

    @Transactional
    public ReadingDtos.ImportResult importCsv(MultipartFile file) {
        List<Map<String, Object>> errors = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        Map<String, Device> byCode = new HashMap<>();
        devices.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getOrgId, CurrentUser.orgId()))
                .forEach(d -> byCode.put(d.getCode(), d));
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
                    if (c.length < 6) throw new IllegalArgumentException("列数不足，需 6 列");
                    Device d = byCode.get(c[0].trim());
                    if (d == null) throw new IllegalArgumentException("设备编码不存在: " + c[0].trim());
                    String rawTs = c[1].trim().replace("T", " ");
                    if (rawTs.length() == 10) rawTs = rawTs + " 00:00";
                    if (rawTs.length() < 16) throw new IllegalArgumentException("时间格式应为 yyyy-MM-dd HH:mm");
                    LocalDateTime ts = LocalDateTime.parse(rawTs.substring(0, 16), TS_FMT);
                    BigDecimal qty = new BigDecimal(c[2].trim());
                    BigDecimal rate = c[4].isBlank() ? BigDecimal.ONE : new BigDecimal(c[4].trim());
                    guard.assertEditable(ts.toLocalDate().toString().substring(0, 7));
                    ReadingDtos.CreateReq req = new ReadingDtos.CreateReq(
                            d.getId(), ts, qty, c[3].trim(), rate,
                            c[5].trim(), "正常", "CSV 导入", null);
                    create(req);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    errors.add(Map.of("line", no, "reason", e.getMessage() == null ? "解析失败" : e.getMessage()));
                }
            }
        } catch (Exception e) {
            throw BizException.validation("CSV 解析失败: " + e.getMessage());
        }
        return new ReadingDtos.ImportResult(ok, failed, errors);
    }

    private ReadingDtos.View toView(DeviceReading r, Device d) {
        return new ReadingDtos.View(r.getId(), r.getDeviceId(),
                d == null ? "" : d.getCode(), d == null ? "" : d.getName(),
                r.getTs(), r.getQty(), r.getUnit(), r.getRate(),
                r.getSource(), r.getStatus(), r.getRemark(), r.getVersion());
    }
}
