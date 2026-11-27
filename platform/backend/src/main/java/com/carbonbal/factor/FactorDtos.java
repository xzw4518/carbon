package com.carbonbal.factor;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class FactorDtos {

    public record CreateReq(
            @NotBlank(message = "类别必填") String category,
            @NotBlank(message = "能源品种必填") String fuel,
            @NotBlank(message = "单位必填") String unit,
            @NotNull(message = "因子值必填") @DecimalMin(value = "0", inclusive = false, message = "因子值必须大于 0") BigDecimal value,
            @NotBlank(message = "来源文档必填") String sourceDoc,
            @NotBlank(message = "层级必填") String scope,
            @NotBlank(message = "版本必填") String version,
            @NotNull(message = "生效日期必填") LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record ReviewReq(@NotNull(message = "审核结论必填") Boolean approve) {}
}
