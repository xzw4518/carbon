package com.carbonbal.audit;

import com.carbonbal.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租户内审计日志：只读，仅租户管理员（含代管的超级管理员）可查。 */
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @RequestParam(required = false) String entity,
                            @RequestParam(required = false) String action,
                            @RequestParam(required = false) String actor,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        return ApiResponse.ok(audit.page(page, size, entity, action, actor, keyword, from, to));
    }

    @GetMapping("/facets")
    public ApiResponse facets() {
        return ApiResponse.ok(audit.facets());
    }
}
