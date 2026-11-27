package com.carbonbal.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public class DeviceDtos {
    public record DeviceReq(
            @NotBlank(message = "设备名称必填") String name,
            @NotBlank(message = "设备编码必填") String code,
            @NotBlank(message = "能源种类必填") String energyType,
            String unit,
            String status,
            Long facilityId,
            String deviceType,
            String level,
            String remark,
            Integer version) {}

    public record DeviceView(
            Long id, String name, String code, String energyType, String unit, String status,
            Long facilityId, String facilityName, String deviceType, String level, String remark,
            Integer version, Boolean disabled) {}

    public record ImportResult(int success, int failed, List<Map<String, Object>> errors) {}
}
