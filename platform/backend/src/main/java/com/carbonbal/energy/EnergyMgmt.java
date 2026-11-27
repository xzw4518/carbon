package com.carbonbal.energy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("energy_mgmt")
public class EnergyMgmt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private LocalDate atDate;
    private String orgName;
    private String duty;
    private String team;
    private String post;
    private String charge;
    private String filler;
    private String contact;
    private String systemName;
    private String report;
    private String filing;
    private String instName;
    private String stat;
    private String promo;
    private String training;
    private String subsidy;
    private Integer disabled;
    private Integer version;
    private String remark;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
