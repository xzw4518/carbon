package com.carbonbal.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("supplier")
public class Supplier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    /** 上级供应商；为空表示根节点（一级供应商） */
    private Long parentId;
    private String name;
    private String product;
    private String unit;
    private String fpUnit;
    private BigDecimal footprint;
    private LocalDate date;
    private String target;
    private String level;
    private Integer sort;
    private String remark;
    private Integer disabled;
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
