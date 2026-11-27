package com.carbonbal.device;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public class ReadingDtos {

    public record CreateReq(
            @NotNull(message = "设备必填") Long deviceId,
            @NotNull(message = "时间必填") LocalDateTime ts,
            @NotNull(message = "数值必填") @DecimalMin(value = "0", message = "数值不能为负") BigDecimal qty,
            @NotBlank(message = "单位必填") String unit,
            BigDecimal rate,
            @NotBlank(message = "来源必填") String source,
            String status,
            String remark,
            String clientRequestId) {}

    public record UpdateReq(
            @NotNull(message = "版本号必填") Integer version,
            BigDecimal qty,
            String unit,
            BigDecimal rate,
            String source,
            String status,
            String remark) {}

    public record View(
            Long id, Long deviceId, String deviceCode, String deviceName,
            LocalDateTime ts, BigDecimal qty, String unit, BigDecimal rate,
            String source, String status, String remark, Integer version) {}

    public record ImportResult(int success, int failed, java.util.List<Map<String, Object>> errors) {}
}
