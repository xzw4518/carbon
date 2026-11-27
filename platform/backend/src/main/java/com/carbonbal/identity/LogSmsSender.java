package com.carbonbal.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 开发/演示用：只打日志，不真发短信。日志里做脱敏，不输出完整手机号。 */
@Component
public class LogSmsSender implements SmsSender {
    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    @Override
    public void send(String phone, String content) {
        String masked = phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        log.info("[SMS] to={} content={}", masked, content);
    }
}
