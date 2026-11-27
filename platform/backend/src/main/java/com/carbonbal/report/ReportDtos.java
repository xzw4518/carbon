package com.carbonbal.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ReportDtos {

    public record GenerateReq(String type, String period, Long footprintId) {}

    public record Meta(Long id, String type, String period, Long periodId, Long refId,
                       String periodLabel, Integer calcVersion, String fileName, Integer fileSize,
                       String sha256, String generatedBy, LocalDateTime generatedAt) {}

    public record EnergyLine(String fuel, BigDecimal activity, String unit,
                             BigDecimal tce, BigDecimal emission, BigDecimal share) {}

    public record EnergySummary(String period, List<EnergyLine> lines, BigDecimal totalTce,
                                BigDecimal totalEmission, BigDecimal outputValue) {}
}
