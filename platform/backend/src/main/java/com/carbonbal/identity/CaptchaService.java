package com.carbonbal.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Hashes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/** 图形验证码：发送短信前必须通过，2 分钟有效、一次性、最多试 3 次。 */
@Service
public class CaptchaService {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CaptchaMapper mapper;
    private final boolean enabled;
    private final boolean loginEnabled;

    public CaptchaService(CaptchaMapper mapper,
                          @Value("${app.auth.captcha.enabled:true}") boolean enabled,
                          @Value("${app.auth.captcha.login-enabled:true}") boolean loginEnabled) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.loginEnabled = loginEnabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 密码登录是否强制验证码；单独开关便于压测与联调，生产保持开启。 */
    public void verifyLogin(String captchaId, String code, String ip) {
        if (!enabled || !loginEnabled) return;
        verify(captchaId, code, ip);
    }

    public record Challenge(String captchaId, String image) {}

    public Challenge create(String ip) {
        String code = randomCode(4);
        String id = UUID.randomUUID().toString().replace("-", "");
        Captcha c = new Captcha();
        c.setCaptchaId(id);
        c.setCodeHash(Hashes.sha256(code.toLowerCase()));
        c.setAttempts(0);
        c.setExpiresAt(LocalDateTime.now().plusMinutes(2));
        c.setIp(ip);
        c.setCreatedAt(LocalDateTime.now());
        mapper.insert(c);
        return new Challenge(id, svg(code));
    }

    public void verify(String captchaId, String code, String ip) {
        if (!enabled) return;
        if (captchaId == null || captchaId.isBlank() || code == null || code.isBlank()) {
            throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "请先填写图形验证码");
        }
        Captcha c = mapper.selectOne(Wrappers.<Captcha>lambdaQuery()
                .eq(Captcha::getCaptchaId, captchaId).last("LIMIT 1"));
        if (c == null) throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码无效，请刷新");
        if (c.getUsedAt() != null) throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码已使用，请刷新");
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码已过期，请刷新");
        }
        if (c.getAttempts() != null && c.getAttempts() >= 3) {
            throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码错误次数过多，请刷新");
        }
        int rows = mapper.update(null, Wrappers.<Captcha>lambdaUpdate()
                .eq(Captcha::getId, c.getId())
                .setSql("attempts = attempts + 1"));
        if (rows == 0) throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码校验失败，请刷新");
        if (!Hashes.sha256(code.trim().toLowerCase()).equals(c.getCodeHash())) {
            throw new BizException(ErrorCode.AUTH_CAPTCHA_INVALID, "图形验证码不正确");
        }
        mapper.update(null, Wrappers.<Captcha>lambdaUpdate()
                .eq(Captcha::getId, c.getId()).isNull(Captcha::getUsedAt)
                .set(Captcha::getUsedAt, LocalDateTime.now()));
    }

    private static String randomCode(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static String svg(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='112' height='40' viewBox='0 0 112 40'>");
        sb.append("<rect width='112' height='40' fill='#f4f7f6'/>");
        for (int i = 0; i < 4; i++) {
            int x1 = RANDOM.nextInt(112), y1 = RANDOM.nextInt(40), x2 = RANDOM.nextInt(112), y2 = RANDOM.nextInt(40);
            sb.append("<line x1='").append(x1).append("' y1='").append(y1)
              .append("' x2='").append(x2).append("' y2='").append(y2)
              .append("' stroke='#c9d6d2' stroke-width='1'/>");
        }
        for (int i = 0; i < code.length(); i++) {
            int x = 12 + i * 24 + RANDOM.nextInt(4);
            int y = 27 + RANDOM.nextInt(4) - 2;
            int rot = RANDOM.nextInt(20) - 10;
            sb.append("<text x='").append(x).append("' y='").append(y)
              .append("' font-family='monospace' font-size='22' font-weight='700' fill='#0f7a5a'")
              .append(" transform='rotate(").append(rot).append(" ").append(x).append(" ").append(y).append(")'>")
              .append(code.charAt(i)).append("</text>");
        }
        sb.append("</svg>");
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
