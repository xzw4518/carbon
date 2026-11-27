package com.carbonbal.accounting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class AccountingDtos {

    public record VersionReq(@NotNull(message = "版本号必填") Integer version) {}

    public record UnlockReq(
            @NotNull(message = "版本号必填") Integer version,
            @NotBlank(message = "解锁原因必填") String reason) {}

    public record LineView(
            Long id, Long readingId, Long deviceId, String deviceName,
            Long factorId, String factorVersion, String scopeType,
            BigDecimal activity, String activityUnit, BigDecimal factorValue, BigDecimal emission) {}

    public record UnlockView(Long id, String reason, String requestedBy, String approvedBy, LocalDateTime at) {}

    public record PeriodView(
            Long id, String period, String status,
            BigDecimal scope1, BigDecimal scope2, BigDecimal total,
            Integer calcVersion, Integer version,
            LocalDateTime lockedAt, String lockedBy,
            String submittedBy, LocalDateTime submittedAt,
            String reviewedBy, LocalDateTime reviewedAt,
            String approvedBy, LocalDateTime approvedAt,
            List<LineView> lines, List<UnlockView> unlocks,
            /** 是否存在"同一人提交并批准"的解锁记录（P2-1 语义校正后的显式标记）。 */
            boolean selfApproved) {}
}
