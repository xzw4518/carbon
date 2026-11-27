package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sms_code")
public class SmsCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String scene;
    private String codeHash;
    private Integer attempts;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private String ip;
    private String providerMsgId;
    private String sendStatus;
    private String errorMsg;
    private LocalDateTime createdAt;
}
