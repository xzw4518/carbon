package com.carbonbal.evidence;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @GetMapping("/evidences")
    public ApiResponse evidences(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String validState) {
        return ApiResponse.ok(service.listEvidences(page, size, keyword, from, to, validState));
    }

    @GetMapping("/evidences/{id}")
    public ApiResponse evidence(@PathVariable Long id) {
        return ApiResponse.ok(service.getEvidence(id));
    }

    @PostMapping("/evidences")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createEvidence(@Valid @RequestBody EvidenceDtos.EvidenceReq req) {
        return ApiResponse.ok(service.createEvidence(req));
    }

    @PutMapping("/evidences/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateEvidence(@PathVariable Long id, @Valid @RequestBody EvidenceDtos.EvidenceReq req) {
        return ApiResponse.ok(service.updateEvidence(id, req));
    }

    @DeleteMapping("/evidences/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeEvidence(@PathVariable Long id) {
        service.removeEvidence(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/third-party-reports")
    public ApiResponse thirdReports(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.listThirdReports(page, size, keyword, from, to));
    }

    @GetMapping("/third-party-reports/{id}")
    public ApiResponse thirdReport(@PathVariable Long id) {
        return ApiResponse.ok(service.getThirdReport(id));
    }

    @PostMapping("/third-party-reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createThirdReport(@Valid @RequestBody EvidenceDtos.ThirdReportReq req) {
        return ApiResponse.ok(service.createThirdReport(req));
    }

    @PutMapping("/third-party-reports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateThirdReport(@PathVariable Long id, @Valid @RequestBody EvidenceDtos.ThirdReportReq req) {
        return ApiResponse.ok(service.updateThirdReport(id, req));
    }

    @DeleteMapping("/third-party-reports/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeThirdReport(@PathVariable Long id) {
        service.removeThirdReport(id);
        return ApiResponse.ok(null);
    }
}
