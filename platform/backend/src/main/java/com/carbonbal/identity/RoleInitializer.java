package com.carbonbal.identity;

import com.carbonbal.org.OrgMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐角色数据：每个企业必须有默认角色【运营】，且拥有全部页面。
 * 幂等，可随页面目录扩展重复执行。
 */
@Component
public class RoleInitializer {

    private static final Logger log = LoggerFactory.getLogger(RoleInitializer.class);

    private final OrgMapper orgs;
    private final RoleService roles;

    public RoleInitializer(OrgMapper orgs, RoleService roles) {
        this.orgs = orgs;
        this.roles = roles;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        int n = 0;
        for (var o : orgs.selectList(null)) {
            roles.ensureDefault(o.getId());
            n++;
        }
        log.info("角色初始化完成：{} 个租户已确保默认角色", n);
    }
}
