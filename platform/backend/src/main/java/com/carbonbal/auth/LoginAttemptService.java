package com.carbonbal.auth;

import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定：按 key 累计失败次数，达到阈值后锁定 M 分钟。
 * <p>
 * 两把锁共用一套实现（P1-3）：
 * <ul>
 *   <li>{@code ip|账号|IP} 维度，阈值 {@code app.auth.max-attempts}（默认 5）——挡同一来源的撞库；</li>
 *   <li>{@code acct|账号} 维度，阈值 {@code app.auth.max-attempts-account}（默认 20）——阈值更高以免被
 *       他人恶意锁定（DoS），同时让"轮换 X-Forwarded-For"无法重置计数。</li>
 * </ul>
 * 单实例内存实现，重启清零；多实例部署需换成 Redis/数据库计数。
 */
@Component
public class LoginAttemptService {

    private final int maxAttempts;
    private final int maxAttemptsAccount;
    private final Duration lockFor;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(@Value("${app.auth.max-attempts:5}") int maxAttempts,
                               @Value("${app.auth.max-attempts-account:20}") int maxAttemptsAccount,
                               @Value("${app.auth.lock-minutes:15}") int lockMinutes) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxAttemptsAccount = Math.max(1, Math.max(maxAttemptsAccount, this.maxAttempts));
        this.lockFor = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    /** 默认（账号+IP）维度阈值。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 账号维度阈值。 */
    public int maxAttemptsAccount() {
        return maxAttemptsAccount;
    }

    /** 使用默认阈值检查锁定状态。 */
    public void check(String key) {
        check(key, maxAttempts);
    }

    /**
     * 检查锁定状态，命中锁定时抛出 429。
     *
     * @param key   锁定键
     * @param limit 该键使用的失败阈值（用于锁定时长提示与自愈判断）
     */
    public void check(String key, int limit) {
        Attempt a = attempts.get(key);
        if (a == null) return;
        synchronized (a) {
            if (a.lockedUntil != null) {
                if (a.lockedUntil.isAfter(Instant.now())) {
                    long minutes = Math.max(1, Duration.between(Instant.now(), a.lockedUntil).toMinutes() + 1);
                    throw new BizException(ErrorCode.AUTH_RATE_LIMITED, "登录失败次数过多，请 " + minutes + " 分钟后再试");
                }
                attempts.remove(key);
            }
        }
    }

    /** 使用默认阈值记一次失败。 */
    public void fail(String key) {
        fail(key, maxAttempts);
    }

    /**
     * 记一次失败；累计达到 {@code limit} 即锁定。
     *
     * @param key   锁定键
     * @param limit 该键的失败阈值
     */
    public void fail(String key, int limit) {
        int threshold = Math.max(1, limit);
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (a) {
            a.count++;
            if (a.count >= threshold) {
                a.lockedUntil = Instant.now().plus(lockFor);
            }
        }
    }

    /** 登录成功后清空该键的失败计数（两个维度都要调用）。 */
    public void success(String key) {
        attempts.remove(key);
    }

    /** 仅供测试/运维排查：清空全部计数。 */
    public void resetAll() {
        attempts.clear();
    }

    private static final class Attempt {
        private int count;
        private Instant lockedUntil;
    }
}
