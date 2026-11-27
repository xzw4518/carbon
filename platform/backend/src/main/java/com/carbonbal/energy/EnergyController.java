package com.carbonbal.energy;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class EnergyController {
    private final EnergyService service;

    public EnergyController(EnergyService service) {
        this.service = service;
    }

    @GetMapping("/energy-mgmt")
    public ApiResponse mgmt(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size,
                            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listMgmt(page, size, keyword));
    }

    @GetMapping("/energy-mgmt/{id}")
    public ApiResponse mgmtDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.detailMgmt(id));
    }

    @PostMapping("/energy-mgmt")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createMgmt(@Valid @RequestBody EnergyDtos.MgmtReq req) {
        return ApiResponse.ok(service.createMgmt(req));
    }

    @PutMapping("/energy-mgmt/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateMgmt(@PathVariable Long id, @Valid @RequestBody EnergyDtos.MgmtReq req) {
        return ApiResponse.ok(service.updateMgmt(id, req));
    }

    @GetMapping("/retrofits")
    public ApiResponse retrofits(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) Integer year,
                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listRetrofit(page, size, year, keyword));
    }

    @GetMapping("/retrofits/{id}")
    public ApiResponse retrofitDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.detailRetrofit(id));
    }

    @PostMapping("/retrofits")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createRetrofit(@Valid @RequestBody EnergyDtos.RetrofitReq req) {
        return ApiResponse.ok(service.createRetrofit(req));
    }

    @PutMapping("/retrofits/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateRetrofit(@PathVariable Long id, @Valid @RequestBody EnergyDtos.RetrofitReq req) {
        return ApiResponse.ok(service.updateRetrofit(id, req));
    }

    @DeleteMapping("/retrofits/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeRetrofit(@PathVariable Long id) {
        service.removeRetrofit(id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/energy-mgmt/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeMgmt(@PathVariable Long id) {
        service.removeMgmt(id);
        return ApiResponse.ok(null);
    }
}
