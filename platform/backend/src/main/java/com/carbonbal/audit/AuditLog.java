package com.carbonbal.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long actorId;
    private String actionLabel;
    private String entity;
    private String entityId;
    private String action;
    private String beforeJson;
    private String afterJson;
    private String actor;
    private LocalDateTime loggedAt;
}
