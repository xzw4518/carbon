package com.carbonbal.output;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("production_value")
public class ProductionValue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String period;
    private Long productId;
    private String productName;
    private BigDecimal outputValue;
    private String unit;
    private BigDecimal qty;
    private String qtyUnit;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
