package com.carbonbal.device;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device")
public class Device {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
    private String code;
    private String energyType;
    private String unit;
    private String status;
    private Long facilityId;
    private String deviceType;
    private String level;
    private String remark;
    private Integer version;
    private Boolean disabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
