package com.carbonbal.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class AnalysisDtos {

    public record Kpi(String label, String value, String unit, String foot) {}

    public record Series(String name, String type, String unit, List<String> labels, List<BigDecimal> values) {}

    public record Column(String key, String label, String type) {}

    public record Table(String title, List<Column> columns, List<Map<String, Object>> rows) {}

    public record SankeyNode(String id, String name, BigDecimal value) {}

    public record SankeyLink(String from, String to, BigDecimal value) {}

    public record Sankey(List<List<SankeyNode>> layers, List<SankeyLink> links) {}

    /** 六个分析页统一响应：KPI + 图序列 + 明细表 + 可选桑基。 */
    public record View(List<Kpi> kpis, List<Series> series, Table table, Sankey sankey) {}

    public record MonthPoint(String period, BigDecimal energy, BigDecimal emission) {}

    public record Todo(String title, String meta, String action, String route) {}

    public record AlertBrief(Long id, String title, String level, String value, String occurredAt, String status) {}

    public record Quota(BigDecimal total, BigDecimal used, BigDecimal ratio, BigDecimal remaining) {}

    public record Overview(Long orgId, String orgName, String orgShortName, Integer year,
                           List<Kpi> kpis, List<MonthPoint> monthly, List<Todo> todos,
                           List<AlertBrief> alerts, Quota quota) {}
}
