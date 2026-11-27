package com.carbonbal.factor;

import com.carbonbal.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emission-factors")
public class FactorController {
    private final FactorService service;

    public FactorController(FactorService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String fuel,
                            @RequestParam(required = false) String scope,
                            @RequestParam(required = false) String reviewStatus) {
        return ApiResponse.ok(service.list(fuel, scope, reviewStatus));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse create(@Valid @RequestBody FactorDtos.CreateReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse review(@PathVariable Long id, @Valid @RequestBody FactorDtos.ReviewReq req) {
        return ApiResponse.ok(service.review(id, req.approve()));
    }
}
