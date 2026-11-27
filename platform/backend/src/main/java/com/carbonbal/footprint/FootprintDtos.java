package com.carbonbal.footprint;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class FootprintDtos {

    public record Req(
            Long productId,
            @NotBlank(message = "产品名称必填") String name,
            @NotBlank(message = "单位必填") String unit,
            @NotNull(message = "数量必填") @DecimalMin(value = "0.0001", message = "数量必须大于 0") BigDecimal qty,
            @NotNull(message = "足迹值必填") @DecimalMin(value = "0", message = "足迹不能为负") BigDecimal footprint,
            @NotNull(message = "核算日期必填") LocalDate date,
            @NotBlank(message = "核算边界必填") String boundary,
            BigDecimal stageRaw,
            BigDecimal stageTransport,
            BigDecimal stageMake,
            Integer version, String remark) {}
}
