package com.carbonbal.quota;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quota_year")
public class QuotaYear {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Integer quotaYear;
    private BigDecimal totalQty;
    private BigDecimal nextYearQty;
    private String note;
    private Integer version;
    private Integer disabled;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
