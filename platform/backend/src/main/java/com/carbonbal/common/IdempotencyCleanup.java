package com.carbonbal.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 幂等记录过期清理：每小时删一次 24h 前的记录，避免表无限增长。 */
@Component
public class IdempotencyCleanup {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);
    private final JdbcTemplate jdbc;

    public IdempotencyCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purge() {
        try {
            int n = jdbc.update("delete from idempotency_record where expires_at < ?", LocalDateTime.now());
            if (n > 0) log.info("幂等记录清理 {} 条", n);
        } catch (Exception e) {
            log.warn("幂等记录清理失败：{}", e.getMessage());
        }
    }
}
