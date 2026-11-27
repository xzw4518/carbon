package com.carbonbal.report;

import com.carbonbal.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse generate(@RequestBody ReportDtos.GenerateReq req) {
        return ApiResponse.ok(service.generate(req.type(), req.period(), req.footprintId()));
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String type,
                            @RequestParam(required = false) String period,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.list(type, period, page, size));
    }

    @GetMapping("/bundle")
    public ResponseEntity<byte[]> bundle(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String period) {
        ReportService.Bundle bundle = service.bundle(type, period);
        String encoded = java.net.URLEncoder.encode(bundle.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bundle.bytes());
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Report r = service.download(id);
        String encoded = java.net.URLEncoder.encode(r.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-SHA256", r.getSha256())
                .contentType(MediaType.APPLICATION_PDF)
                .body(r.getContent());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.ok(null);
    }
}
