package com.carbonbal.factor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("emission_factor")
public class EmissionFactor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String category;
    private String fuel;
    private String unit;
    private BigDecimal factorValue;
    private String sourceDoc;
    private String scope;
    private Long orgId;
    private String version;
    private String reviewStatus;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String createdBy;
    private LocalDateTime createdAt;
}
