package com.carbonbal.device;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/device-readings")
public class ReadingController {
    private final ReadingService service;

    public ReadingController(ReadingService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public ApiResponse page(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size,
                            @RequestParam(required = false) Long deviceId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(service.page(page, size, deviceId, keyword, status, from, to));
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Long deviceId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(service.list(deviceId, from, to));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse create(@Valid @RequestBody ReadingDtos.CreateReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse update(@PathVariable Long id, @Valid @RequestBody ReadingDtos.UpdateReq req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.importCsv(file));
    }
}
