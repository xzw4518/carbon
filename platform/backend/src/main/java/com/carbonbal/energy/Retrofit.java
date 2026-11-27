package com.carbonbal.energy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("retrofit")
public class Retrofit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Integer projectYear;
    private String name;
    private BigDecimal invest;
    private String investUnit;
    private BigDecimal saving;
    private String savingUnit;
    private String contract;
    private String done;
    private LocalDate atDate;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
