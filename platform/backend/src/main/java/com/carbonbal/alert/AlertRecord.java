package com.carbonbal.alert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("alert_record")
public class AlertRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long ruleId;
    private String title;
    private String level;
    private String metric;
    private BigDecimal metricValue;
    private BigDecimal threshold;
    private String unit;
    private String deviceCode;
    private LocalDateTime occurredAt;
    private String status;
    private Integer isRead;
    private String handledBy;
    private LocalDateTime handledAt;
    private String remark;
    private Integer disabled;
    private LocalDateTime createdAt;
}
