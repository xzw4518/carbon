package com.carbonbal.org;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("org")
public class Org {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String status;
    private String plan;
    private Integer seatLimit;
    /** 授权开始日期；到期时间复用 expireAt（当天 23:59:59）。 */
    private LocalDate licenseStartAt;
    private java.time.LocalDateTime expireAt;
    private java.time.LocalDateTime suspendedAt;
    private Long parentOrgId;
    private Integer orgLevel;
    private String tenantCode;
    private String timezone;
    private String code;
    private String shortName;
    private String orgType;
    private String industry;
    private String scale;
    private String region;
    private String legalPerson;
    private LocalDate regDate;
    private String regCapital;
    private String contact;
    private String phone;
    private String email;
    private String intro;
    private String remark;
    private Long logoFileId;
    private Long licenseFileId;
    private Integer version;
    private String updatedBy;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
