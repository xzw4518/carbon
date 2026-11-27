package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账号与租户的成员关系；角色挂在关系上，同一人在不同企业可有不同角色。 */
@Data
@TableName("user_tenant")
public class UserTenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long userId;
    /** 层级：tenant_admin（租户管理员）/ user（普通用户）。 */
    private String userType;
    /** 普通用户绑定的角色；租户管理员为 null。 */
    private Long roleId;
    /** 兼容旧注解：tenant_admin → admin，普通用户 → user。 */
    private String role;
    private String status;
    private String employeeNo;
    private String title;
    private String invitedBy;
    private LocalDateTime joinedAt;
    private LocalDateTime removedAt;
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
