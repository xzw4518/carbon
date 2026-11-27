package com.carbonbal.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public class AuthDtos {

    /** 登录入参：新客户端只传 phone；username 保留一版，供既有脚本与回滚使用。 */
    public record LoginReq(String phone, String username, String password, Long orgId,
                           String captchaId, String captchaCode) {
        public String account() {
            if (phone != null && !phone.isBlank()) return phone.trim();
            if (username != null && !username.isBlank()) return username.trim();
            return "";
        }
    }

    public record SmsLoginReq(@NotBlank(message = "手机号必填") String phone,
                              @NotBlank(message = "验证码必填") String code,
                              Long orgId) {}

    public record SmsSendReq(@NotBlank(message = "手机号必填") String phone,
                             String scene, String captchaId, String captchaCode) {}

    public record TenantSelectReq(@NotBlank(message = "临时令牌必填") String tempToken,
                                  @NotNull(message = "企业必选") Long orgId) {}

    public record ChangePwdReq(@NotBlank(message = "原密码必填") String oldPassword,
                               @NotBlank(message = "新密码必填") String newPassword) {}

    public record ResetPwdReq(String newPassword) {}

    public record LoginResp(String token, Map<String, Object> user, String tempToken,
                            List<Map<String, Object>> tenants, Boolean needTenantSelect,
                            Boolean mustChangePassword, Integer passwordExpireInDays) {}

    public record MeResp(Map<String, Object> user, List<Map<String, Object>> tenants,
                         Long currentOrgId, String role, List<String> permissions,
                         Integer passwordExpireInDays) {}
}
