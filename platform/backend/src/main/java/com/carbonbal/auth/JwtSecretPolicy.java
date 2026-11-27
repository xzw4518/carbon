package com.carbonbal.auth;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * JWT 密钥策略：长度、熵、已知默认值黑名单的<b>唯一</b>判定入口。
 * <p>
 * 设计取舍：弱密钥一旦上线，任何人都能自签管理员令牌（P0-2），所以这里刻意 fail-fast——
 * 校验不通过就抛异常让应用启动失败，不提供"警告后继续"的静默通道。
 * <p>
 * 判定三条：
 * <ol>
 *   <li>长度 ≥ {@link #MIN_LENGTH}（32 字节，同时满足 HS256/HS384/HS512 的密钥下限）；</li>
 *   <li>去重字符数 ≥ {@link #MIN_DISTINCT_CHARS}（挡住 {@code aaaa...}、{@code 1234...} 这类低熵值）；</li>
 *   <li>不以前缀黑名单中的任一值开头（挡住历史默认密钥与常见占位文本）。</li>
 * </ol>
 */
public final class JwtSecretPolicy {

    /** 密钥最小长度（字符数；ASCII 下等于字节数）。 */
    public static final int MIN_LENGTH = 32;

    /** 去重字符数下限：低于该值的字符串熵不足，容易被字典穷举。 */
    public static final int MIN_DISTINCT_CHARS = 12;

    /** dev 环境自动生成的临时密钥长度。 */
    public static final int GENERATED_LENGTH = 48;

    /**
     * 已知默认密钥 / 占位文本前缀（小写比较）。
     * 新增历史默认值时必须追加到这里，否则校验会被绕过。
     */
    private static final List<String> KNOWN_BAD_PREFIXES = List.of(
            "carbonbal-dev-secret",
            "dev-only-key",
            "local-dev-",
            "please-replace",
            "changeme",
            "change-me",
            "secret");

    /** 生成临时密钥用的字符集：去掉易混字符，便于在日志/台账里人工核对。 */
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private JwtSecretPolicy() {}

    /**
     * 是否为已知的默认/占位密钥。
     *
     * @param secret 待判定的密钥，可为 null
     * @return null、空白或以黑名单前缀开头时返回 true
     */
    public static boolean isKnownBad(String secret) {
        if (secret == null) return true;
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        for (String bad : KNOWN_BAD_PREFIXES) {
            if (normalized.startsWith(bad)) return true;
        }
        return false;
    }

    /**
     * 校验密钥，不合格直接抛出 {@link IllegalStateException}（应用启动失败）。
     *
     * @param secret 待校验的密钥
     * @throws IllegalStateException 密钥为空、过短、熵不足或命中黑名单
     */
    public static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置。请执行 bash deploy/gen-secret.sh 生成随机密钥并注入环境变量 JWT_SECRET。");
        }
        String value = secret.trim();
        if (isKnownBad(value)) {
            throw new IllegalStateException(
                    "检测到已知默认密钥。生产/测试环境必须替换为随机密钥（bash deploy/gen-secret.sh），"
                            + "否则任何人都能自签管理员令牌。");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET 长度不足 " + MIN_LENGTH + " 位（当前 " + value.length()
                            + " 位）。请执行 bash deploy/gen-secret.sh 重新生成。");
        }
        if (distinctChars(value) < MIN_DISTINCT_CHARS) {
            throw new IllegalStateException(
                    "JWT_SECRET 熵不足（去重字符数 " + distinctChars(value) + " < " + MIN_DISTINCT_CHARS
                            + "）。请执行 bash deploy/gen-secret.sh 重新生成随机密钥。");
        }
    }

    /**
     * 生成一个满足 {@link #validate(String)} 的随机密钥（仅 dev 自动兜底使用）。
     *
     * @return 48 位随机密钥
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 去重字符数：低熵探测的量化口径。 */
    static int distinctChars(String value) {
        return (int) value.chars().distinct().count();
    }
}
