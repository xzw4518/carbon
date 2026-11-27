package com.carbonbal.quota;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class QuotaDtos {
    public record QuotaReq(
            @NotNull(message = "年度必填") Integer year,
            @NotNull(message = "配额量必填") @DecimalMin(value = "0", message = "配额量不能为负") BigDecimal total,
            @DecimalMin(value = "0", message = "下年度配额不能为负") BigDecimal next,
            String note,
            Integer version) {}
}
