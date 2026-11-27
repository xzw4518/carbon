package com.carbonbal.alert;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
public class AlertController {
    private final AlertService service;
    private final AlertRecordService recordService;

    public AlertController(AlertService service, AlertRecordService recordService) {
        this.service = service;
        this.recordService = recordService;
    }

    @GetMapping("/alert-rules")
    public ApiResponse rules(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int size,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String level,
                             @RequestParam(required = false) Integer enabled) {
        return ApiResponse.ok(service.listRules(page, size, keyword, level, enabled));
    }

    @GetMapping("/alert-rules/{id}")
    public ApiResponse ruleDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.detailRule(id));
    }

    @PostMapping("/alert-rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createRule(@Valid @RequestBody AlertDtos.RuleReq req) {
        return ApiResponse.ok(service.createRule(req));
    }

    @PutMapping("/alert-rules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateRule(@PathVariable Long id, @Valid @RequestBody AlertDtos.RuleReq req) {
        return ApiResponse.ok(service.updateRule(id, req));
    }

    @DeleteMapping("/alert-rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeRule(@PathVariable Long id) {
        service.removeRule(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/alert-events/page")
    public ApiResponse eventPage(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String type) {
        return ApiResponse.ok(service.pageEvents(page, size, keyword, type));
    }

    @GetMapping("/alert-events")
    public ApiResponse events(@RequestParam(required = false) String type) {
        return ApiResponse.ok(service.listEvents(type));
    }

    @PostMapping("/alert-events")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createEvent(@Valid @RequestBody AlertDtos.EventReq req) {
        return ApiResponse.ok(service.createEvent(req));
    }

    @PutMapping("/alert-events/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateEvent(@PathVariable Long id, @Valid @RequestBody AlertDtos.EventReq req) {
        return ApiResponse.ok(service.updateEvent(id, req));
    }

    @PostMapping("/alert-events/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse toggleEvent(@PathVariable Long id, @RequestParam Integer enabled) {
        return ApiResponse.ok(service.toggleEvent(id, enabled));
    }

    @GetMapping("/alert-records")
    public ApiResponse records(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size,
                               @RequestParam(required = false) String level,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) Integer isRead,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String metric,
                               @RequestParam(required = false) String device,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(recordService.list(page, size, level, status, isRead, keyword, metric, device, from, to));
    }

    @GetMapping("/alert-records/stats")
    public ApiResponse recordStats() {
        return ApiResponse.ok(recordService.stats());
    }

    @PostMapping("/alert-records/{id}/read")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse read(@PathVariable Long id) {
        return ApiResponse.ok(recordService.markRead(id));
    }

    @PostMapping("/alert-records/read-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse readAll() {
        return ApiResponse.ok(java.util.Map.of("affected", recordService.markAllRead()));
    }

    @PostMapping("/alert-records/{id}/handle")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse handle(@PathVariable Long id, @RequestBody(required = false) AlertDtos.HandleReq req) {
        return ApiResponse.ok(recordService.handle(id, req == null ? null : req.remark()));
    }

    @PostMapping("/alert-records/batch-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse batchDelete(@Valid @RequestBody AlertDtos.BatchReq req) {
        return ApiResponse.ok(java.util.Map.of("affected", recordService.batchRemove(req.ids())));
    }
}
