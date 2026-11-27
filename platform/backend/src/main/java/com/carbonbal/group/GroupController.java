package com.carbonbal.group;

import com.carbonbal.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/group")
public class GroupController {

    private final GroupService service;

    public GroupController(GroupService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse summary(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.summary(year));
    }
}
