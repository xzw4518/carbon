package com.carbonbal.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("carbon_trade")
public class CarbonTrade {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long assetId;
    private String assetName;
    private String type;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private String party;
    private String usePurpose;
    private LocalDate tradeDate;
    private String status;
    private Integer disabled;
    private Integer version;
    private String createdBy;
    private LocalDateTime createdAt;
}
