package com.carbonbal.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已吊销的会话（令牌 jti）。
 * <p>
 * 一个 jti 对应"一次登录"：登出只吊销本次会话，同一账号在其他设备上的会话不受影响。
 * {@code expires_at} 取令牌自身的过期时间，过期后由定时任务清理，避免表无限增长。
 * <p>
 * 该表属于身份域（无租户隔离），已在 {@code OrgTenantHandler#IGNORE} 中豁免。
 */
@Data
@TableName("revoked_token")
public class RevokedToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 令牌唯一标识（JWT 标准声明 jti）。 */
    private String jti;

    private Long userId;

    /** 吊销时所在的租户，仅用于运维排查（本表不做租户隔离）。 */
    private Long orgId;

    private String phone;

    /** 吊销原因：logout / password-change / admin-kick 等。 */
    private String reason;

    /** 令牌自身的过期时间；到期后记录可安全删除。 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
