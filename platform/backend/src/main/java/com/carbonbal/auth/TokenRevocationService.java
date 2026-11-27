package com.carbonbal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话吊销（BUG-001）：登出后令牌立即失效，且重启后依然失效。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>持久化是唯一事实来源</b>：吊销记录落 {@code revoked_token} 表，进程重启不丢失；
 *       内存 Map 只做"已吊销"的正向缓存（一次写入、永不回退），不存在把已吊销误判为有效的可能；</li>
 *   <li><b>按会话（jti）吊销</b>：登出只让当前设备下线，同一账号的其他设备会话仍然有效；</li>
 *   <li><b>自动收敛</b>：记录带 {@code expires_at}（=令牌过期时间），过期后由定时任务清理，
 *       登出时也会顺带做一次带节流的清理，避免长期不重启的实例上表无限增长。</li>
 * </ol>
 * 多实例部署时本类无需改动：每个实例都直接读同一张表（缓存只是"已吊销"的短路径）。
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    /** 登出触发的清理最小间隔：1 小时内最多清理一次，避免每次登出都打一次 DELETE。 */
    private static final long PURGE_MIN_INTERVAL_MILLIS = 3600_000L;

    private final RevokedTokenMapper tokens;

    /** jti → 令牌过期时间（毫秒）。只缓存"已吊销"，命中即拒绝，不受写入时间影响。 */
    private final Map<String, Long> revokedCache = new ConcurrentHashMap<>();

    private final AtomicLong lastPurgeAt = new AtomicLong(0L);

    public TokenRevocationService(RevokedTokenMapper tokens) {
        this.tokens = tokens;
    }

    /**
     * 吊销一条会话。
     *
     * @param jti       令牌唯一标识（JWT 的 jti 声明），空白时直接忽略
     * @param userId    账号 id，可为 null（老令牌）
     * @param orgId     吊销时所在租户，可为 null
     * @param phone     账号手机号，可为 null
     * @param reason    吊销原因（logout 等）
     * @param expiresAt 令牌过期时间；为空或已过期时无需落库
     * @return 本次是否新写入了一条吊销记录（已吊销过返回 false）
     */
    public boolean revoke(String jti, Long userId, Long orgId, String phone, String reason, Instant expiresAt) {
        if (jti == null || jti.isBlank()) return false;
        Instant exp = expiresAt == null ? Instant.now() : expiresAt;
        if (!exp.isAfter(Instant.now())) {
            // 令牌本身已过期：验签阶段就会拒绝，无需占用吊销表
            revokedCache.put(jti, exp.toEpochMilli());
            return false;
        }
        LocalDateTime expiresLocal = LocalDateTime.ofInstant(exp, ZoneId.systemDefault());
        RevokedToken row = new RevokedToken();
        row.setJti(jti);
        row.setUserId(userId);
        row.setOrgId(orgId);
        row.setPhone(phone == null || phone.length() <= 32 ? phone : phone.substring(0, 32));
        row.setReason(reason == null || reason.isBlank() ? "logout" : reason);
        row.setExpiresAt(expiresLocal);
        row.setCreatedAt(LocalDateTime.now());
        try {
            tokens.insert(row);
            revokedCache.put(jti, exp.toEpochMilli());
            return true;
        } catch (DataIntegrityViolationException e) {
            // 唯一索引冲突 = 并发/重复登出，语义上已经吊销成功
            revokedCache.put(jti, exp.toEpochMilli());
            log.debug("会话 {} 已吊销过，忽略重复写入", jti);
            return false;
        }
    }

    /**
     * 该会话是否已被吊销。
     * <p>
     * 先查正向缓存，未命中再查库；查库结果只缓存命中（已吊销），
     * 因此"刚吊销的令牌"在任何实例上最多多一次库查询，不会被缓存误判为有效。
     *
     * @param jti 令牌唯一标识
     * @return 已吊销返回 true
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        Long cached = revokedCache.get(jti);
        long now = System.currentTimeMillis();
        if (cached != null && cached > now) return true;
        if (cached != null) revokedCache.remove(jti, cached);
        RevokedToken row = tokens.selectOne(Wrappers.<RevokedToken>lambdaQuery()
                .eq(RevokedToken::getJti, jti).last("LIMIT 1"));
        if (row == null || row.getExpiresAt() == null) return false;
        long expMillis = row.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (expMillis <= now) return false;
        revokedCache.put(jti, expMillis);
        return true;
    }

    /**
     * 清理已过期（令牌已到期）的吊销记录。
     * <p>
     * 令牌过期后验签必然失败，吊销记录不再有安全价值，可安全删除。
     *
     * @return 本次删除的记录数
     */
    public int purgeExpired() {
        int removed = tokens.delete(Wrappers.<RevokedToken>lambdaQuery()
                .le(RevokedToken::getExpiresAt, LocalDateTime.now()));
        lastPurgeAt.set(System.currentTimeMillis());
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        revokedCache.forEach((k, v) -> { if (v <= now) stale.add(k); });
        stale.forEach(revokedCache::remove);
        if (removed > 0) log.info("清理过期会话吊销记录 {} 条", removed);
        return removed;
    }

    /** 登出时顺带清理（带 1 小时节流）；清理失败不能影响登出本身。 */
    public void purgeExpiredIfDue() {
        long now = System.currentTimeMillis();
        long last = lastPurgeAt.get();
        if (now - last < PURGE_MIN_INTERVAL_MILLIS) return;
        if (!lastPurgeAt.compareAndSet(last, now)) return;
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.warn("会话吊销表清理失败：{}", e.getMessage());
        }
    }

    /** 每天 03:50 清理过期记录，避免长期运行的表无限增长。 */
    @Scheduled(cron = "0 50 3 * * *")
    public void purgeExpiredDaily() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.warn("定时清理会话吊销表失败：{}", e.getMessage());
        }
    }
}
