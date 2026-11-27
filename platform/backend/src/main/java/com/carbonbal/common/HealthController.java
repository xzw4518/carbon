package com.carbonbal.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 轻量健康检查：给负载均衡/监控用，不需要登录。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", LocalDateTime.now().toString());
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            m.put("db", one != null && one == 1 ? "UP" : "DOWN");
        } catch (Exception e) {
            m.put("status", "DEGRADED");
            m.put("db", "DOWN");
        }
        return ApiResponse.ok(m);
    }
}
