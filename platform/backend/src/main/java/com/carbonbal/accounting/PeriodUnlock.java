package com.carbonbal.accounting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("period_unlock")
public class PeriodUnlock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long periodId;
    private String reason;
    private String requestedBy;
    private String approvedBy;
    private LocalDateTime unlockedAt;
}
