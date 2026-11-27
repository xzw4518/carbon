package com.carbonbal.report;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("report")
public class Report {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    /** carbon / energy / footprint */
    private String reportType;
    private Long periodId;
    private Long refId;
    private String periodLabel;
    private Integer calcVersion;
    private String fileName;
    private Integer fileSize;
    private String sha256;
    private byte[] content;
    private Integer disabled;
    private String generatedBy;
    private LocalDateTime generatedAt;
}
