package com.carbonbal.evidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public class EvidenceDtos {

    public record FileMeta(Long id, String name, String ext, Long size, String sha256) {}

    public record EvidenceReq(
            @NotBlank(message = "凭证名称必填") String name,
            String code,
            String description,
            @NotNull(message = "有效期开始必填") LocalDate validFrom,
            LocalDate validTo,
            @NotNull(message = "凭证日期必填") LocalDate atDate,
            List<Long> fileIds,
            Integer version, String remark) {}

    public record EvidenceView(
            Long id, String name, String code, String description,
            LocalDate validFrom, LocalDate validTo, LocalDate atDate,
            Integer version, List<FileMeta> files, String remark) {}

    public record ThirdReportReq(
            @NotBlank(message = "报告名称必填") String name,
            String description,
            @NotNull(message = "有效期开始必填") LocalDate validFrom,
            LocalDate validTo,
            @NotNull(message = "报告日期必填") LocalDate atDate,
            Long fileId,
            Integer version, String remark) {}

    public record ThirdReportView(
            Long id, String name, String description,
            LocalDate validFrom, LocalDate validTo, LocalDate atDate,
            Integer version, FileMeta file, String remark) {}
}
