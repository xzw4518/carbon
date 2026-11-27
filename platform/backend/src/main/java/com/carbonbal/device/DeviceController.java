package com.carbonbal.device;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String energyType,
                            @RequestParam(required = false) Boolean includeDisabled) {
        return ApiResponse.ok(service.list(keyword, energyType, includeDisabled));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse create(@Valid @RequestBody DeviceDtos.DeviceReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse update(@PathVariable Long id, @Valid @RequestBody DeviceDtos.DeviceReq req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse disable(@PathVariable Long id) {
        service.disable(id, true);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse enable(@PathVariable Long id) {
        service.disable(id, false);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.importCsv(file));
    }
}
