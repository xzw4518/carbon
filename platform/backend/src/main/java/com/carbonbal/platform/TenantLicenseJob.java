package com.carbonbal.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.org.Org;
import com.carbonbal.org.OrgMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户授权期任务：每天 00:05 把到期的租户置为 expired、把开始日期已到的置为 active。
 * <p>
 * 状态变化会清掉该租户成员的会话缓存，配合请求级校验实现“到期即下线”。
 * 登录与请求本身也按到期时间实时判断，所以即使任务未跑，也不会放行过期租户。
 */
@Component
public class TenantLicenseJob {

    private static final Logger log = LoggerFactory.getLogger(TenantLicenseJob.class);

    private final OrgMapper orgs;
    private final PlatformService platform;

    public TenantLicenseJob(OrgMapper orgs, PlatformService platform) {
        this.orgs = orgs;
        this.platform = platform;
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void scheduled() {
        runOnce();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    /** 返回本轮变更的租户数量，便于巡检与测试断言。 */
    public int runOnce() {
        LocalDate today = LocalDate.now();
        int changed = 0;

        List<Org> activatable = orgs.selectList(Wrappers.<Org>lambdaQuery()
                .eq(Org::getStatus, "pending")
                .le(Org::getLicenseStartAt, today));
        for (Org o : activatable) {
            if (o.getExpireAt() != null && o.getExpireAt().isBefore(LocalDateTime.now())) continue;
            orgs.update(null, Wrappers.<Org>lambdaUpdate()
                    .eq(Org::getId, o.getId()).set(Org::getStatus, "active"));
            platform.invalidateTenant(o.getId());
            changed++;
        }

        List<Org> expiring = orgs.selectList(Wrappers.<Org>lambdaQuery()
                .eq(Org::getStatus, "active")
                .isNotNull(Org::getExpireAt)
                .lt(Org::getExpireAt, LocalDateTime.now()));
        for (Org o : expiring) {
            orgs.update(null, Wrappers.<Org>lambdaUpdate()
                    .eq(Org::getId, o.getId()).set(Org::getStatus, "expired"));
            platform.invalidateTenant(o.getId());
            changed++;
        }

        if (changed > 0) log.info("租户授权期任务：{} 个租户状态已更新", changed);
        return changed;
    }
}
