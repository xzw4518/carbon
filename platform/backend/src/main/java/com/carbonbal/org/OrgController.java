package com.carbonbal.org;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/org")
public class OrgController {
    private final OrgService service;

    public OrgController(OrgService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse get() {
        return ApiResponse.ok(service.get());
    }

    /** 租户管理员查看本企业授权期（只读）。 */
    @GetMapping("/license")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse license() {
        return ApiResponse.ok(service.license());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@Valid @RequestBody OrgDtos.OrgReq req) {
        return ApiResponse.ok(service.update(req));
    }
}
