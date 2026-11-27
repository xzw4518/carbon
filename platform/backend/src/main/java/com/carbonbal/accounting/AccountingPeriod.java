package com.carbonbal.accounting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("accounting_period")
public class AccountingPeriod {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String period;
    private String status;
    private BigDecimal scope1;
    private BigDecimal scope2;
    private BigDecimal total;
    private Integer calcVersion;
    private Integer version;
    private LocalDateTime lockedAt;
    private String lockedBy;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
