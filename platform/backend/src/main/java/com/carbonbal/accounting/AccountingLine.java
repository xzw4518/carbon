package com.carbonbal.accounting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("accounting_line")
public class AccountingLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long periodId;
    private Integer calcVersion;
    private Long readingId;
    private Long deviceId;
    private Long factorId;
    private String scopeType;
    private BigDecimal activity;
    private String activityUnit;
    private BigDecimal factorValue;
    private BigDecimal emission;
    private LocalDateTime createdAt;
}
