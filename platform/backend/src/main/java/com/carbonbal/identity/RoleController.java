package com.carbonbal.identity;

import com.carbonbal.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 角色管理：仅租户管理员可用，角色只配置可进入的页面。 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize("hasRole('ADMIN')")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list() {
        return ApiResponse.ok(service.list());
    }

    /** 页面目录：13 个分组、34 个页面，前端角色弹窗直接渲染。 */
    @GetMapping("/pages")
    public ApiResponse pages() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", PageCatalog.CODES.size());
        m.put("groups", PageCatalog.GROUPS);
        return ApiResponse.ok(m);
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/{id}/users")
    public ApiResponse users(@PathVariable Long id) {
        List<Map<String, Object>> rows = service.usersOf(id);
        return ApiResponse.ok(Map.of("total", rows.size(), "rows", rows));
    }
}
