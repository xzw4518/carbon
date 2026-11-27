package com.carbonbal.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Hashes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短信验证码：6 位、5 分钟有效、错误 5 次作废。
 * 频控：同号 60 秒一条、每天 10 条；同 IP 每小时 20 条。
 * 返回给前端的永远是「已发送」，避免手机号被枚举。
 */
@Service
public class SmsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsCodeMapper mapper;
    private final SmsSender sender;
    private final String fixedCode;
    private final boolean exposeCode;

    public SmsService(SmsCodeMapper mapper, SmsSender sender,
                      @Value("${app.sms.fixed-code:}") String fixedCode,
                      @Value("${app.sms.expose-code:false}") boolean exposeCode) {
        this.mapper = mapper;
        this.sender = sender;
        this.fixedCode = fixedCode == null ? "" : fixedCode.trim();
        this.exposeCode = exposeCode;
    }

    /** 演示环境可把验证码回显给前端；生产必须关闭。 */
    public boolean exposeCode() {
        return exposeCode;
    }

    /** 演示模式：固定码或回显；生产：随机 6 位且只在短信里出现。 */
    public boolean demoMode() {
        return exposeCode || !fixedCode.isEmpty();
    }

    /** 发送并返回本次验证码（仅 demo 模式返回，生产返回 null）。 */
    public String send(String phone, String scene, String ip) {
        String sc = scene == null || scene.isBlank() ? "login" : scene;
        LocalDateTime now = LocalDateTime.now();

        Long lastMinute = mapper.selectCount(Wrappers.<SmsCode>lambdaQuery()
                .eq(SmsCode::getPhone, phone)
                .gt(SmsCode::getCreatedAt, now.minusSeconds(60)));
        if (lastMinute != null && lastMinute > 0) {
            throw new BizException(ErrorCode.AUTH_CODE_LIMIT, "发送过于频繁，请稍后再试");
        }
        Long today = mapper.selectCount(Wrappers.<SmsCode>lambdaQuery()
                .eq(SmsCode::getPhone, phone)
                .gt(SmsCode::getCreatedAt, LocalDate.now().atStartOfDay()));
        if (today != null && today >= 10) {
            throw new BizException(ErrorCode.AUTH_CODE_LIMIT, "今日验证码发送次数已达上限，请明天再试");
        }
        if (ip != null && !ip.isBlank()) {
            Long ipCount = mapper.selectCount(Wrappers.<SmsCode>lambdaQuery()
                    .eq(SmsCode::getIp, ip)
                    .gt(SmsCode::getCreatedAt, now.minusHours(1)));
            if (ipCount != null && ipCount >= 20) {
                throw new BizException(ErrorCode.AUTH_CODE_LIMIT, "请求过于频繁，请稍后再试");
            }
        }

        String code = fixedCode.isEmpty() ? randomCode() : fixedCode;
        SmsCode row = new SmsCode();
        row.setPhone(phone);
        row.setScene(sc);
        row.setCodeHash(Hashes.sha256(code));
        row.setAttempts(0);
        row.setExpiresAt(now.plusMinutes(5));
        row.setIp(ip);
        row.setSendStatus("sent");
        row.setCreatedAt(now);
        try {
            sender.send(phone, "验证码 " + code + "，5 分钟内有效。请勿转发给他人。");
        } catch (Exception e) {
            row.setSendStatus("failed");
            row.setErrorMsg(e.getMessage() != null && e.getMessage().length() > 250
                    ? e.getMessage().substring(0, 250) : e.getMessage());
        }
        mapper.insert(row);
        if ("failed".equals(row.getSendStatus())) {
            throw BizException.internal("短信发送失败，请稍后再试");
        }
        return exposeCode ? code : null;
    }

    public void verify(String phone, String scene, String code) {
        String sc = scene == null || scene.isBlank() ? "login" : scene;
        if (code == null || code.isBlank()) throw new BizException(ErrorCode.AUTH_CODE_INVALID, "请输入验证码");
        SmsCode row = mapper.selectOne(Wrappers.<SmsCode>lambdaQuery()
                .eq(SmsCode::getPhone, phone).eq(SmsCode::getScene, sc)
                .isNull(SmsCode::getUsedAt)
                .orderByDesc(SmsCode::getId).last("LIMIT 1"));
        if (row == null) throw new BizException(ErrorCode.AUTH_CODE_INVALID, "请先获取验证码");
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.AUTH_CODE_EXPIRED, "验证码已过期，请重新获取");
        }
        if (row.getAttempts() != null && row.getAttempts() >= 5) {
            throw new BizException(ErrorCode.AUTH_CODE_EXPIRED, "验证码错误次数过多，请重新获取");
        }
        // 先自增尝试次数，避免并发下无限试错
        mapper.update(null, Wrappers.<SmsCode>lambdaUpdate()
                .eq(SmsCode::getId, row.getId())
                .setSql("attempts = attempts + 1"));
        if (!Hashes.sha256(code.trim()).equals(row.getCodeHash())) {
            throw new BizException(ErrorCode.AUTH_CODE_INVALID, "验证码不正确");
        }
        // 原子消费：受影响行数=1 才算成功，天然幂等
        int used = mapper.update(null, Wrappers.<SmsCode>lambdaUpdate()
                .eq(SmsCode::getId, row.getId())
                .isNull(SmsCode::getUsedAt)
                .set(SmsCode::getUsedAt, LocalDateTime.now()));
        if (used == 0) throw new BizException(ErrorCode.AUTH_CODE_EXPIRED, "验证码已使用，请重新获取");
    }

    /** 每天清理 7 天前的验证码与过期图形码。 */
    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        LocalDateTime before = LocalDateTime.now().minusDays(7);
        mapper.delete(Wrappers.<SmsCode>lambdaQuery().lt(SmsCode::getCreatedAt, before));
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }
}
