package com.carbonbal.output;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("total_value")
public class TotalValue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String period;
    private String name;
    private BigDecimal qty;
    private String qtyUnit;
    private BigDecimal outputValue;
    private String unit;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
