package com.carbonbal.device;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("device_reading")
public class DeviceReading {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long deviceId;
    private LocalDateTime ts;
    private BigDecimal qty;
    private String unit;
    private BigDecimal rate;
    private String source;
    private String status;
    private String remark;
    private Integer version;
    private String clientRequestId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
