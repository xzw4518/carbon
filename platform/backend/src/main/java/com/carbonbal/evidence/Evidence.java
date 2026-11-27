package com.carbonbal.evidence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("evidence")
public class Evidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
    private String code;
    private String description;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDate atDate;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
