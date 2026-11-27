package com.carbonbal.asset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public class AssetDtos {

    public record AssetReq(
            @NotBlank(message = "资产名称必填") @Size(max = 120, message = "名称最长 120 字") String name,
            @NotBlank(message = "资产类型必填") String type,
            @NotNull(message = "数量必填") @DecimalMin(value = "0", message = "数量不能为负") BigDecimal qty,
            @NotBlank(message = "单位必填") String unit,
            @NotNull(message = "年度必填")
            @Min(value = 2000, message = "年度不能早于 2000")
            @Max(value = 2100, message = "年度不能晚于 2100") Integer year,
            String status,
            LocalDate expireDate,
            Integer version,
            @Size(max = 500, message = "备注最长 500 字") String remark) {}

    public record TradeReq(
            @NotNull(message = "资产必填") Long assetId,
            @NotBlank(message = "交易类型必填") String type,
            @NotNull(message = "数量必填") @DecimalMin(value = "0.0001", message = "数量必须大于 0") BigDecimal qty,
            @NotNull(message = "单价必填") @DecimalMin(value = "0", message = "单价不能为负") BigDecimal price,
            String party,
            String usePurpose,
            @NotNull(message = "交易日期必填") LocalDate tradeDate) {}
}
