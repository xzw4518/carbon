package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 角色 → 页面授权。 */
@Data
@TableName("tenant_role_page")
public class TenantRolePage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long roleId;
    private String pageCode;
}
