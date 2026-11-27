package com.carbonbal.footprint;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("product_footprint")
public class ProductFootprint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long productId;
    private String name;
    private String unit;
    private BigDecimal qty;
    private BigDecimal footprint;
    private LocalDate date;
    private String boundary;
    private BigDecimal stageRaw;
    private BigDecimal stageTransport;
    private BigDecimal stageMake;
    private String status;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
