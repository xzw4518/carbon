package com.carbonbal.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String phone;
    private String email;
    private String passwordHash;
    private String passwordAlgo;
    private String displayName;
    private String role;
    private Long orgId;
    private Boolean enabled;
    private String status;
    private Integer tokenVersion;
    private Boolean mustChangePassword;
    private Boolean platformAdmin;
    private LocalDateTime passwordUpdatedAt;
    private LocalDateTime tempPasswordExpiresAt;
    private Long lastOrgId;
    private Integer failedCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
}
