package com.carbonbal.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MasterDataDtos {

    public record ProductReq(
            @NotBlank(message = "名称必填") @Size(max = 120, message = "名称最长 120 字") String name,
            @NotBlank(message = "编码必填") @Size(max = 64, message = "编码最长 64 字") String code,
            @Size(max = 64, message = "规格型号最长 64 字") String model,
            @NotBlank(message = "产品类型必填")
            @Pattern(regexp = "一般产品|低碳产品|零碳产品", message = "产品类型只能是 一般产品 / 低碳产品 / 零碳产品")
            String type,
            @NotBlank(message = "产品分类必填")
            @Pattern(regexp = "工业产品|农业产品|其他产品", message = "产品分类只能是 工业产品 / 农业产品 / 其他产品")
            String cls,
            @NotBlank(message = "单位必填") @Size(max = 16, message = "单位最长 16 字") String unit,
            @DecimalMin(value = "0", message = "单位产品能耗值不能为负") BigDecimal l1,
            @DecimalMin(value = "0", message = "单位产品能耗值不能为负") BigDecimal l2,
            @DecimalMin(value = "0", message = "单位产品能耗值不能为负") BigDecimal l3,
            Integer version,
            @Size(max = 500, message = "备注最长 500 字") String remark) {}

    public record SupplierReq(
            @NotBlank(message = "供应商必填") String name,
            @NotBlank(message = "供货产品必填") String product,
            @NotBlank(message = "单位必填") String unit,
            @NotBlank(message = "足迹单位必填") String fpUnit,
            @NotNull(message = "足迹值必填") @DecimalMin(value = "0", message = "足迹不能为负") BigDecimal footprint,
            @NotNull(message = "日期必填") LocalDate date,
            String target, String level, Integer sort,
            Integer version, Long parentId,
            @Size(max = 500, message = "备注最长 500 字") String remark) {}
}
