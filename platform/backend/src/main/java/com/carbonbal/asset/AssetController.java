package com.carbonbal.asset;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/carbon-assets")
public class AssetController {
    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.list(page, size, keyword, type, year));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse create(@Valid @RequestBody AssetDtos.AssetReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse update(@PathVariable Long id, @Valid @RequestBody AssetDtos.AssetReq req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.ok(null);
    }
}
