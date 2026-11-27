package com.carbonbal.job;

import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final ScheduledJobs jobs;

    public JobController(ScheduledJobs jobs) {
        this.jobs = jobs;
    }

    /** 任务清单只给运维台与管理员，普通用户无需看到定时任务。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse list() {
        return ApiResponse.ok(ScheduledJobs.jobNames());
    }

    @PostMapping("/{name}/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse run(@PathVariable String name) {
        try {
            return ApiResponse.ok(jobs.runOnce(name, CurrentUser.orgId()));
        } catch (IllegalArgumentException e) {
            throw BizException.validation(e.getMessage());
        } catch (Exception e) {
            throw BizException.internal("任务执行失败：" + e.getMessage());
        }
    }
}
