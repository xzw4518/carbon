package com.carbonbal.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 轻量摘要工具：验证码、密码历史等只需要不可逆校验，不必上 bcrypt。 */
public final class Hashes {
    private Hashes() {}

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    /** 日志与前端展示用：138****0001。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone == null ? "" : phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
