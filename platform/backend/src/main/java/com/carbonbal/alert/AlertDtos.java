package com.carbonbal.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class AlertDtos {

    public record RuleReq(
            @NotBlank(message = "规则名称必填") String name,
            @NotBlank(message = "指标必填") String metric,
            @NotBlank(message = "条件必填") String op,
            @NotNull(message = "阈值必填") @DecimalMin(value = "0", message = "阈值不能为负") BigDecimal threshold,
            @NotBlank(message = "单位必填") String unit,
            Integer cool,
            @NotBlank(message = "等级必填") String level,
            Integer enabled,
            String remark,
            Integer version) {}

    public record EventReq(
            @NotBlank(message = "事件名称必填") String name,
            @NotBlank(message = "事件类型必填") String type,
            String metric, String op, BigDecimal threshold, String level,
            Integer enabled, String remark) {}

    public record HandleReq(String remark) {}

    public record BatchReq(@NotNull(message = "ids 必填") List<Long> ids) {}
}
