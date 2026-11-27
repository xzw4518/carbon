package com.carbonbal.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("phone_history")
public class PhoneHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String oldPhone;
    private String newPhone;
    private String changedBy;
    private LocalDateTime changedAt;
}
