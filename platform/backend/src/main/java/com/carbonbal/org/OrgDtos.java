package com.carbonbal.org;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class OrgDtos {

    public record FileRef(Long id, String name) {}

    public record OrgView(
            Long id, String name, String shortName, String orgType, String code,
            String industry, String scale, String region, String legalPerson,
            LocalDate regDate, String regCapital, String contact, String phone, String email,
            String intro, String remark, Long logoFileId, Long licenseFileId,
            Integer version, FileRef logo, FileRef license) {}

    public record OrgReq(
            @NotBlank(message = "企业名称必填") String name,
            String shortName,
            String orgType,
            @NotBlank(message = "统一社会信用代码必填") String code,
            String industry,
            String scale,
            String region,
            String legalPerson,
            LocalDate regDate,
            String regCapital,
            String contact,
            String phone,
            String email,
            String intro,
            String remark,
            Long logoFileId,
            Long licenseFileId,
            Integer version) {}
}
