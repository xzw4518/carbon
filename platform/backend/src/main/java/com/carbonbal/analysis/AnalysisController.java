package com.carbonbal.analysis;

import com.carbonbal.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse overview(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.overview(year));
    }

    @GetMapping("/analysis/energy-consumption")
    public ApiResponse energyConsumption(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.energyConsumption(from, to));
    }

    @GetMapping("/analysis/carbon-emission")
    public ApiResponse carbonEmission(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.carbonEmission(from, to));
    }

    @GetMapping("/analysis/energy-efficiency")
    public ApiResponse energyEfficiency(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.energyEfficiency(from, to));
    }

    @GetMapping("/analysis/energy-flow")
    public ApiResponse energyFlow(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.energyFlow(from, to));
    }

    @GetMapping("/analysis/strategy")
    public ApiResponse strategy(@RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.strategy(from, to));
    }

    @GetMapping("/analysis/quota")
    public ApiResponse quota(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.quota(year));
    }

    @GetMapping("/analysis/footprint")
    public ApiResponse footprint(@RequestParam(required = false) Long productId,
                                 @RequestParam(required = false) String product,
                                 @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.footprintAnalysis(productId, product, year));
    }

    @GetMapping("/analysis/supply-chain")
    public ApiResponse supplyChain(@RequestParam(required = false) String supplier,
                                   @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.supplyChainAnalysis(supplier, year));
    }
}
