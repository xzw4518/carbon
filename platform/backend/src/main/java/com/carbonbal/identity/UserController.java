package com.carbonbal.identity;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 账号与成员管理：仅企业管理员可写；成员本人可看自己的登录记录。 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String role,
                            @RequestParam(required = false) Long roleId,
                            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(keyword, role, roleId, status));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse create(@Valid @RequestBody UserDtos.CreateReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{membershipId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@PathVariable Long membershipId, @RequestBody UserDtos.UpdateReq req) {
        service.update(membershipId, req);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{membershipId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse disable(@PathVariable Long membershipId) {
        service.disable(membershipId, true);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{membershipId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse enable(@PathVariable Long membershipId) {
        service.disable(membershipId, false);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{membershipId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse remove(@PathVariable Long membershipId) {
        service.remove(membershipId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{membershipId}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse resetPassword(@PathVariable Long membershipId) {
        return ApiResponse.ok(service.resetPassword(membershipId));
    }

    @PostMapping("/{membershipId}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse unlock(@PathVariable Long membershipId) {
        service.unlock(membershipId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{membershipId}/handover")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse handover(@PathVariable Long membershipId, @RequestBody Map<String, Long> body) {
        Long to = body.get("toMembershipId");
        if (to == null) throw com.carbonbal.common.BizException.validation("请选择接手人");
        return ApiResponse.ok(service.handover(membershipId, to));
    }

    @GetMapping("/{membershipId}/login-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse loginLogs(@PathVariable Long membershipId,
                                 @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(service.loginLogsOf(membershipId, limit));
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.importCsv(file));
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ByteArrayResource> export() {
        byte[] bytes = service.exportCsv();
        String name = URLEncoder.encode("成员列表.csv", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(new ByteArrayResource(bytes));
    }
}
