package com.carbonbal.alert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("alert_event")
public class AlertEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
    private String type;
    private String metric;
    private String op;
    private BigDecimal threshold;
    private String level;
    private Integer builtin;
    private Integer enabled;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
