package com.carbonbal.platform;

import com.carbonbal.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 平台管理端：只有 platform_admin（超级管理员）可访问，跨租户操作。 */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformController {

    private final PlatformService service;

    public PlatformController(PlatformService service) {
        this.service = service;
    }

    @GetMapping("/tenants")
    public ApiResponse tenants(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "10") int size,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String expiring) {
        return ApiResponse.ok(service.tenants(page, size, keyword, status, expiring));
    }

    @GetMapping("/tenants/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/tenants")
    public ApiResponse create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createTenant(body));
    }

    @PutMapping("/tenants/{id}")
    public ApiResponse update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.updateTenant(id, body));
    }

    @PostMapping("/tenants/{id}/renew")
    public ApiResponse renew(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(service.renew(id, body == null ? Map.of() : body));
    }

    @PostMapping("/tenants/{id}/suspend")
    public ApiResponse suspend(@PathVariable Long id) {
        return ApiResponse.ok(service.suspend(id));
    }

    @PostMapping("/tenants/{id}/resume")
    public ApiResponse resume(@PathVariable Long id) {
        return ApiResponse.ok(service.resume(id));
    }

    @GetMapping("/stats")
    public ApiResponse stats() {
        return ApiResponse.ok(service.stats());
    }

    /** 平台概览：KPI + 到期预警 + 最近平台操作。 */
    @GetMapping("/overview")
    public ApiResponse overview() {
        return ApiResponse.ok(service.overview());
    }

    /** 平台审计：跨租户的开通/续期/停用/恢复/代管操作。 */
    @GetMapping("/audit")
    public ApiResponse audit(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @RequestParam(required = false) String action,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.audit(page, size, action, keyword, from, to));
    }

    @GetMapping("/usage")
    public ApiResponse usage() {
        return ApiResponse.ok(service.usage());
    }
}
