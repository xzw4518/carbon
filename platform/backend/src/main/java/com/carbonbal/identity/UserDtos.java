package com.carbonbal.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

public class UserDtos {

    public record CreateReq(
            @NotBlank(message = "手机号必填")
            @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
            @NotBlank(message = "姓名必填") String displayName,
            /** 兼容旧入参：admin → 租户管理员，其余 → 普通用户。 */
            String role,
            String userType, Long roleId,
            String employeeNo, String title, String email) {}

    public record UpdateReq(
            String displayName, String role, String userType, Long roleId,
            String employeeNo, String title, String email, Integer version) {}

    public record MemberView(
            Long membershipId, Long userId, String phone, String displayName, String email,
            String role, String roleLabel, String status,
            String employeeNo, String title, Integer version,
            Boolean mustChangePassword, String lastLoginAt, String joinedAt,
            String userType, Long roleId, String roleName) {}

    public record CreateResp(Long membershipId, Long userId, String phone, String tempPassword,
                             Boolean existingAccount) {}

    public record ImportResp(int success, int failed, List<Map<String, Object>> errors, String tempPassword) {}
}
