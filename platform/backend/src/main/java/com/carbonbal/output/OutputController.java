package com.carbonbal.output;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OutputController {
    private final OutputService service;

    public OutputController(OutputService service) {
        this.service = service;
    }

    @GetMapping("/production-values")
    public ApiResponse productionValues(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.listProduction(page, size, keyword, from, to));
    }

    @GetMapping("/production-values/{id}")
    public ApiResponse productionValue(@PathVariable Long id) {
        return ApiResponse.ok(service.getProduction(id));
    }

    @PostMapping("/production-values")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createProduction(@Valid @RequestBody OutputDtos.ProductionReq req) {
        return ApiResponse.ok(service.createProduction(req));
    }

    @PutMapping("/production-values/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateProduction(@PathVariable Long id,
                                        @Valid @RequestBody OutputDtos.ProductionReq req) {
        return ApiResponse.ok(service.updateProduction(id, req));
    }

    @DeleteMapping("/production-values/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeProduction(@PathVariable Long id) {
        service.removeProduction(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/total-values")
    public ApiResponse totalValues(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.listTotal(page, size, keyword, from, to));
    }

    @GetMapping("/total-values/{id}")
    public ApiResponse totalValue(@PathVariable Long id) {
        return ApiResponse.ok(service.getTotal(id));
    }

    @PostMapping("/total-values")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse createTotal(@Valid @RequestBody OutputDtos.TotalReq req) {
        return ApiResponse.ok(service.createTotal(req));
    }

    @PutMapping("/total-values/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse updateTotal(@PathVariable Long id, @Valid @RequestBody OutputDtos.TotalReq req) {
        return ApiResponse.ok(service.updateTotal(id, req));
    }

    @DeleteMapping("/total-values/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse removeTotal(@PathVariable Long id) {
        service.removeTotal(id);
        return ApiResponse.ok(null);
    }
}
