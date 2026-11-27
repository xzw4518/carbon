package com.carbonbal.energy;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EnergyDtos {

    public record MgmtReq(
            @NotNull(message = "填报日期必填") LocalDate atDate,
            @NotBlank(message = "机构名称必填") String orgName,
            @NotBlank(message = "负责人必填") String duty,
            String team, String post, String charge, String filler, String contact,
            String systemName, String report, String filing, String instName,
            String stat, String promo, String training, String subsidy,
            Integer version, String remark) {}

    public record RetrofitReq(
            @NotNull(message = "年度必填") Integer projectYear,
            @NotBlank(message = "项目名称必填") String name,
            @NotNull(message = "投资额必填") @DecimalMin(value = "0", message = "投资额不能为负") BigDecimal invest,
            @NotBlank(message = "投资单位必填") String investUnit,
            @NotNull(message = "节能量必填") @DecimalMin(value = "0", message = "节能量不能为负") BigDecimal saving,
            @NotBlank(message = "节能单位必填") String savingUnit,
            String contract,
            String done,
            LocalDate atDate,
            Integer version, String remark) {}
}
