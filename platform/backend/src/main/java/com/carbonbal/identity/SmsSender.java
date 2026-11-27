package com.carbonbal.identity;

/** 短信发送抽象：当前是日志实现，接实名通道时替换为阿里云/腾讯云实现即可。 */
public interface SmsSender {
    void send(String phone, String content);
}
