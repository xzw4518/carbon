package com.carbonbal.bootstrap;

import com.carbonbal.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BootstrapController {
    private final BootstrapService service;

    public BootstrapController(BootstrapService service) {
        this.service = service;
    }

    /** 原型前端启动快照：一次返回 data.js 结构的只读数据。 */
    @GetMapping("/bootstrap")
    public ApiResponse snapshot(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.snapshot(year));
    }
}
