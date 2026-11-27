package com.carbonbal.file;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_object")
public class FileObject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
    private String ext;
    private Long size;
    private String sha256;
    private String storageKey;
    private String bizType;
    private String bizId;
    private String uploadedBy;
    private Integer disabled;
    private LocalDateTime createdAt;
}
