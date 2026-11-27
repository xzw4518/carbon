package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("captcha")
public class Captcha {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String captchaId;
    private String codeHash;
    private Integer attempts;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private String ip;
    private LocalDateTime createdAt;
}
