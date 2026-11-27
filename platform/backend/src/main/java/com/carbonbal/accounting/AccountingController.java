package com.carbonbal.accounting;

import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounting-periods")
public class AccountingController {
    private final AccountingService service;

    public AccountingController(AccountingService service) {
        this.service = service;
    }

    @GetMapping("/{period}")
    public ApiResponse get(@PathVariable String period) {
        return ApiResponse.ok(service.get(period));
    }

    @GetMapping("/{period}/lines")
    public ApiResponse lines(@PathVariable String period) {
        return ApiResponse.ok(service.get(period).lines());
    }

    @PostMapping("/{period}/calculate")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse calculate(@PathVariable String period) {
        return ApiResponse.ok(service.calculate(period));
    }

    @PostMapping("/{period}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse submit(@PathVariable String period, @Valid @RequestBody AccountingDtos.VersionReq req) {
        return ApiResponse.ok(service.submit(period, req.version()));
    }

    @PostMapping("/{period}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse review(@PathVariable String period,
                              @RequestBody(required = false) java.util.Map<String, Object> body) {
        int version = versionOf(body);
        String remark = body == null || body.get("remark") == null ? null : body.get("remark").toString();
        return ApiResponse.ok(service.review(period, version, remark));
    }

    @PostMapping("/{period}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse approve(@PathVariable String period,
                               @RequestBody(required = false) java.util.Map<String, Object> body) {
        int version = versionOf(body);
        String remark = body == null || body.get("remark") == null ? null : body.get("remark").toString();
        return ApiResponse.ok(service.approve(period, version, remark));
    }

    @PostMapping("/{period}/lock")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse lock(@PathVariable String period, @Valid @RequestBody AccountingDtos.VersionReq req) {
        return ApiResponse.ok(service.lock(period, req.version()));
    }

    @PostMapping("/{period}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse unlock(@PathVariable String period, @Valid @RequestBody AccountingDtos.UnlockReq req) {
        return ApiResponse.ok(service.unlock(period, req.version(), req.reason()));
    }

    private static int versionOf(java.util.Map<String, Object> body) {
        if (body == null || body.get("version") == null) return 0;
        try {
            return Integer.parseInt(body.get("version").toString().trim());
        } catch (NumberFormatException e) {
            throw BizException.validation("版本号必须为整数");
        }
    }
}
