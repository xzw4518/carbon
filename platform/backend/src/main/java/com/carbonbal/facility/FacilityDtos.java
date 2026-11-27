package com.carbonbal.facility;

import jakarta.validation.constraints.NotBlank;

public class FacilityDtos {

    public record Req(
            @NotBlank(message = "设施名称必填") String name,
            @NotBlank(message = "设施编码必填") String code,
            Long parentId,
            Integer sort,
            String remark,
            Integer version) {}

    public record View(
            Long id, String name, String code, Long parentId, String parentName,
            Integer level, Integer sort, String remark, Integer version) {}
}
