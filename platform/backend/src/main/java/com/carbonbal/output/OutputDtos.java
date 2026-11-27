package com.carbonbal.output;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class OutputDtos {

    private static final String PERIOD_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final String PERIOD_MESSAGE = "月份格式为 YYYY-MM";

    public record ProductionReq(
            @NotBlank(message = "月份必填")
            @Pattern(regexp = PERIOD_PATTERN, message = PERIOD_MESSAGE) String period,
            @NotNull(message = "产品必填") Long productId,
            @NotNull(message = "产值必填") @DecimalMin(value = "0", message = "产值不能为负") BigDecimal value,
            @NotBlank(message = "产值单位必填") String unit,
            @NotNull(message = "产量必填") @DecimalMin(value = "0", message = "产量不能为负") BigDecimal qty,
            @NotBlank(message = "产量单位必填") String qtyUnit,
            Integer version, String remark) {}

    public record TotalReq(
            @NotBlank(message = "月份必填")
            @Pattern(regexp = PERIOD_PATTERN, message = PERIOD_MESSAGE) String period,
            @NotBlank(message = "名称必填") String name,
            @NotNull(message = "产量必填") @DecimalMin(value = "0", message = "产量不能为负") BigDecimal qty,
            @NotBlank(message = "产量单位必填") String qtyUnit,
            @NotNull(message = "产值必填") @DecimalMin(value = "0", message = "产值不能为负") BigDecimal value,
            @NotBlank(message = "产值单位必填") String unit,
            Integer version, String remark) {}
}
