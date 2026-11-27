package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户自定义角色：只描述“能进入哪些页面”。 */
@Data
@TableName("tenant_role")
public class TenantRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
    private String remark;
    private Integer isDefault;
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer disabled;
}
