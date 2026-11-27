package com.carbonbal.auth;

import com.carbonbal.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** 密码策略与生成：长度≥12，含大写、小写、数字、符号四类；不得与最近 3 次重复。 */
@Component
public class PasswordPolicy {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*-_=+?";

    private final int minLength;
    private final int expireDays;
    private final int historySize;

    public PasswordPolicy(@Value("${app.auth.password.min-length:12}") int minLength,
                          @Value("${app.auth.password.expire-days:90}") int expireDays,
                          @Value("${app.auth.password.history:3}") int historySize) {
        this.minLength = Math.max(8, minLength);
        this.expireDays = expireDays;
        this.historySize = Math.max(0, historySize);
    }

    public int expireDays() {
        return expireDays;
    }

    public int historySize() {
        return historySize;
    }

    public List<String> rules() {
        return List.of("长度至少 " + minLength + " 位", "包含大写与小写字母", "包含数字", "包含符号（如 !@#$%）");
    }

    public void validate(String raw) {
        if (raw == null || raw.length() < minLength) {
            throw BizException.validation("密码长度至少 " + minLength + " 位");
        }
        boolean u = raw.chars().anyMatch(Character::isUpperCase);
        boolean l = raw.chars().anyMatch(Character::isLowerCase);
        boolean d = raw.chars().anyMatch(Character::isDigit);
        boolean s = raw.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!(u && l && d && s)) {
            throw BizException.validation("密码需同时包含大写字母、小写字母、数字和符号");
        }
    }

    public boolean expired(java.time.LocalDateTime updatedAt) {
        if (expireDays <= 0 || updatedAt == null) return false;
        return updatedAt.plusDays(expireDays).isBefore(java.time.LocalDateTime.now());
    }

    /** 剩余有效天数；未设置过期返回 null。 */
    public Integer expireInDays(java.time.LocalDateTime updatedAt) {
        if (expireDays <= 0 || updatedAt == null) return null;
        long used = java.time.Duration.between(updatedAt, java.time.LocalDateTime.now()).toDays();
        return (int) Math.max(0, expireDays - used);
    }

    /** 生成满足策略的随机密码，用于批量开通的临时密码。 */
    public String random() {
        SecureRandom r = new SecureRandom();
        List<Character> chars = new ArrayList<>();
        chars.add(UPPER.charAt(r.nextInt(UPPER.length())));
        chars.add(LOWER.charAt(r.nextInt(LOWER.length())));
        chars.add(DIGIT.charAt(r.nextInt(DIGIT.length())));
        chars.add(SYMBOL.charAt(r.nextInt(SYMBOL.length())));
        String all = UPPER + LOWER + DIGIT + SYMBOL;
        while (chars.size() < Math.max(minLength, 12)) chars.add(all.charAt(r.nextInt(all.length())));
        java.util.Collections.shuffle(chars, r);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }
}
