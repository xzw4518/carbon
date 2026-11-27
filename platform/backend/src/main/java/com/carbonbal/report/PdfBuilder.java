package com.carbonbal.report;

import com.carbonbal.accounting.AccountingDtos;
import com.carbonbal.footprint.ProductFootprint;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Component
public class PdfBuilder {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Logger log = LoggerFactory.getLogger(PdfBuilder.class);

    /** 页脚机器可读摘要：默认关闭（客户交付件更干净），自动化环境通过 REPORT_MACHINE_LINE=true 打开。 */
    @Value("${app.report.machine-line:false}")
    private boolean machineLine = true; // 单测直接 new 时保留机器行；Spring 注入后以配置为准（生产默认关闭）

    /**
     * 排版常量。章节标题段落自身带有 1.4 倍行高的行距，表格若紧贴其下，
     * 上边框与表头底色会压住标题字形的下缘，因此标题与表格之间必须留白。
     */
    private static final float SECTION_SPACING_BEFORE = 12f;
    private static final float SECTION_SPACING_AFTER = 2f;
    private static final float TABLE_SPACING_BEFORE = 7f;
    private static final float TABLE_SPACING_AFTER = 8f;
    private static final float CELL_PADDING = 4f;

    /** 纯数字（含负数、小数、百分比）右对齐，与文本列区分。 */
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?%?$");

    private BaseFont cjk() {
        String[] cands = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0"
        };
        for (String c : cands) {
            try {
                return BaseFont.createFont(c, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // 尝试下一个候选字体
            }
        }
        log.warn("未找到中文字体，报告将以英文标签输出");
        return null;
    }

    public byte[] build(AccountingDtos.PeriodView p, String orgName, String generatedBy) {
        BaseFont bf = cjk();
        boolean cn = bf != null;
        Font title = font(bf, 18, Font.BOLD, null);
        Font h2 = font(bf, 13, Font.BOLD, null);
        Font body = font(bf, 10, Font.NORMAL, null);
        Font small = font(bf, 8, Font.NORMAL, Color.GRAY);
        Font th = font(bf, 9, Font.BOLD, null);
        Font td = font(bf, 9, Font.NORMAL, null);
        Color headBg = new Color(235, 242, 238);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(title(cn ? "碳排放核算报告" : "Carbon Emission Accounting Report", title));
            doc.add(meta((cn ? "企业：" : "Organization: ") + orgName, body));
            doc.add(meta((cn ? "核算期：" : "Period: ") + p.period()
                    + (cn ? "    核算版本：v" : "    Version: v") + p.calcVersion(), body));
            doc.add(meta((cn ? "状态：" : "Status: ") + p.status()
                    + (cn ? "    生成时间：" : "    Generated: ") + LocalDateTime.now().format(FMT), body));

            doc.add(section(cn ? "一、核算结果" : "1. Result", h2));
            PdfPTable sum = table(new float[]{3, 2}, 60);
            addHead(sum, new String[]{cn ? "项目" : "Item", cn ? "数值（tCO2e）" : "Value"}, th, headBg);
            addRow(sum, new String[]{"Scope 1（" + (cn ? "直接排放" : "direct") + "）", num(p.scope1())}, td);
            addRow(sum, new String[]{"Scope 2（" + (cn ? "外购能源间接排放" : "indirect") + "）", num(p.scope2())}, td);
            addRow(sum, new String[]{cn ? "合计" : "Total", num(p.total())}, td);
            doc.add(sum);

            doc.add(section(cn ? "二、核算明细" : "2. Details", h2));
            PdfPTable t = table(new float[]{3.0f, 2.0f, 1.4f, 1.3f, 1.6f, 1.4f}, 100);
            addHead(t, new String[]{
                    cn ? "设备" : "Device", cn ? "活动数据" : "Activity", cn ? "单位" : "Unit",
                    cn ? "因子" : "Factor", cn ? "排放量" : "Emission", cn ? "范围" : "Scope"}, th, headBg);
            for (AccountingDtos.LineView l : p.lines()) {
                addRow(t, new String[]{
                        l.deviceName(), num(l.activity()), l.activityUnit(),
                        num(l.factorValue()), num(l.emission()), l.scopeType()}, td);
            }
            doc.add(t);

            doc.add(section(cn ? "三、口径说明" : "3. Methodology", h2));
            doc.add(body(cn
                    ? "1. 因子选取优先级：客户自定义 > 地方指南 > 国家缺省值，仅已审核因子参与计算。\n"
                      + "2. 排放量 = 活动数据 × 换算率 × 因子值，保留 4 位小数后汇总。\n"
                      + "3. 本报告绑定核算版本 v" + p.calcVersion() + "，重算不会修改历史报告。"
                    : "1. Factor priority: customer > local > national, approved only.\n"
                      + "2. Emission = activity x rate x factor, rounded to 4 decimals.\n"
                      + "3. Bound to calc version v" + p.calcVersion() + "; recalculation never alters history.",
                    body));
            if (!p.unlocks().isEmpty()) {
                doc.add(body(cn
                        ? "4. 解锁记录：本期间共解锁 " + p.unlocks().size() + " 次，详见系统审计日志。"
                        : "4. Unlocks: " + p.unlocks().size() + " (see audit log).", body));
            }
            doc.add(footer((cn ? "生成人：" : "Generated by: ") + generatedBy
                    + (cn ? "    核算版本：" : "    version: ") + p.calcVersion(), small));
            // 机器可读摘要：固定 ASCII，便于第三方系统与自动化校验提取（默认关闭）
            Font mono = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
            if (machineLine) doc.add(machine("[MACHINE] period=" + p.period()
                    + " calcVersion=" + p.calcVersion()
                    + " scope1=" + num(p.scope1())
                    + " scope2=" + num(p.scope2())
                    + " total=" + num(p.total()), mono));
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** 能源利用报告：能源品种折标煤、碳排放与单位产值强度。 */
    public byte[] buildEnergy(ReportDtos.EnergySummary s, String orgName, String generatedBy) {
        BaseFont bf = cjk();
        boolean cn = bf != null;
        Font title = font(bf, 18, Font.BOLD, null);
        Font h2 = font(bf, 13, Font.BOLD, null);
        Font body = font(bf, 10, Font.NORMAL, null);
        Font small = font(bf, 8, Font.NORMAL, Color.GRAY);
        Font th = font(bf, 9, Font.BOLD, null);
        Font td = font(bf, 9, Font.NORMAL, null);
        Color headBg = new Color(235, 242, 238);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(title(cn ? "能源利用报告" : "Energy Utilization Report", title));
            doc.add(meta((cn ? "企业：" : "Organization: ") + orgName, body));
            doc.add(meta((cn ? "统计期：" : "Period: ") + s.period()
                    + (cn ? "    生成时间：" : "    Generated: ") + LocalDateTime.now().format(FMT), body));

            doc.add(section(cn ? "一、能源消费" : "1. Energy consumption", h2));
            PdfPTable t = table(new float[]{2.6f, 2.2f, 1.4f, 2.0f, 1.4f}, 100);
            addHead(t, new String[]{
                    cn ? "能源品种" : "Fuel", cn ? "活动数据" : "Activity", cn ? "单位" : "Unit",
                    cn ? "折标煤（tce）" : "tce", cn ? "占比" : "Share"}, th, headBg);
            for (ReportDtos.EnergyLine l : s.lines()) {
                addRow(t, new String[]{l.fuel(), num(l.activity()), l.unit(),
                        num(l.tce()), num(l.share()) + "%"}, td);
            }
            addRow(t, new String[]{cn ? "合计" : "Total", "", "", num(s.totalTce()), "100.00%"}, td);
            doc.add(t);

            doc.add(section(cn ? "二、排放与强度" : "2. Emission & intensity", h2));
            PdfPTable sum = table(new float[]{3, 2}, 60);
            addHead(sum, new String[]{cn ? "指标" : "Metric", cn ? "数值" : "Value"}, th, headBg);
            addRow(sum, new String[]{cn ? "碳排放量（tCO2e）" : "Emission (tCO2e)", num(s.totalEmission())}, td);
            addRow(sum, new String[]{cn ? "工业产值（万元）" : "Output value", num(s.outputValue())}, td);
            BigDecimal intensity = s.outputValue() == null || s.outputValue().signum() == 0
                    ? null : s.totalTce().divide(s.outputValue(), 4, RoundingMode.HALF_UP);
            addRow(sum, new String[]{cn ? "单位产值能耗（tce/万元）" : "Energy per output", num(intensity)}, td);
            BigDecimal carbonIntensity = s.outputValue() == null || s.outputValue().signum() == 0
                    ? null : s.totalEmission().divide(s.outputValue(), 4, RoundingMode.HALF_UP);
            addRow(sum, new String[]{cn ? "单位产值碳排放（tCO2e/万元）" : "Emission per output", num(carbonIntensity)}, td);
            doc.add(sum);

            doc.add(section(cn ? "三、口径说明" : "3. Methodology", h2));
            doc.add(body(cn
                    ? "1. 活动数据 = 读数 × 换算率；折标煤系数：电力 0.1229 tce/MWh、天然气 11.0 tce/万Nm3、\n"
                      + "   柴油 1.4571 tce/t、汽油 1.4714 tce/t、蒸汽 0.0341 tce/GJ。\n"
                      + "2. 碳排放沿用核算因子优先级（客户自定义 > 地方 > 国家），仅已审核因子参与。\n"
                      + "3. 产值优先取产品产值合计，缺失时取生产总值；单位产值指标保留 4 位小数。"
                    : "1. Activity = reading x rate; tce factors: electricity 0.1229/MWh, gas 11.0/10k Nm3,\n"
                      + "   diesel 1.4571/t, gasoline 1.4714/t, steam 0.0341/GJ.\n"
                      + "2. Emission factors: customer > local > national, approved only.\n"
                      + "3. Output value from production values, fallback to total value.", body));
            doc.add(footer((cn ? "生成人：" : "Generated by: ") + generatedBy, small));
            Font mono = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
            if (machineLine) doc.add(machine("[MACHINE] type=energy period=" + s.period()
                    + " tce=" + num(s.totalTce())
                    + " emission=" + num(s.totalEmission())
                    + " output=" + num(s.outputValue()), mono));
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** 产品碳足迹报告：阶段构成与边界口径。 */
    public byte[] buildFootprint(ProductFootprint f, String orgName, String generatedBy) {
        BaseFont bf = cjk();
        boolean cn = bf != null;
        Font title = font(bf, 18, Font.BOLD, null);
        Font h2 = font(bf, 13, Font.BOLD, null);
        Font body = font(bf, 10, Font.NORMAL, null);
        Font small = font(bf, 8, Font.NORMAL, Color.GRAY);
        Font th = font(bf, 9, Font.BOLD, null);
        Font td = font(bf, 9, Font.NORMAL, null);
        Color headBg = new Color(235, 242, 238);

        BigDecimal total = f.getFootprint() == null ? BigDecimal.ZERO : f.getFootprint();
        BigDecimal raw = nz(f.getStageRaw());
        BigDecimal transport = nz(f.getStageTransport());
        BigDecimal make = nz(f.getStageMake());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(title(cn ? "产品碳足迹报告" : "Product Carbon Footprint Report", title));
            doc.add(meta((cn ? "企业：" : "Organization: ") + orgName, body));
            doc.add(meta((cn ? "产品：" : "Product: ") + f.getName()
                    + (cn ? "    核算日期：" : "    Date: ") + f.getDate(), body));
            doc.add(meta((cn ? "边界：" : "Boundary: ") + f.getBoundary()
                    + (cn ? "    功能单位：" : "    Unit: ") + f.getUnit()
                    + (cn ? "    数量：" : "    Qty: ") + num(f.getQty()), body));

            doc.add(section(cn ? "一、足迹结果" : "1. Result", h2));
            PdfPTable sum = table(new float[]{3, 2}, 60);
            addHead(sum, new String[]{cn ? "项目" : "Item", cn ? "数值" : "Value"}, th, headBg);
            addRow(sum, new String[]{cn ? "碳足迹（tCO2e/" + f.getUnit() + "）" : "Footprint",
                    num(total)}, td);
            addRow(sum, new String[]{cn ? "核算数量（" + f.getUnit() + "）" : "Quantity", num(f.getQty())}, td);
            doc.add(sum);

            doc.add(section(cn ? "二、阶段构成" : "2. Life-cycle stages", h2));
            PdfPTable t = table(new float[]{3, 2, 1.6f}, 80);
            addHead(t, new String[]{cn ? "阶段" : "Stage", cn ? "数值" : "Value", cn ? "占比" : "Share"}, th, headBg);
            addRow(t, new String[]{cn ? "原材料获取" : "Raw material", num(raw),
                    num(share(raw, total)) + "%"}, td);
            addRow(t, new String[]{cn ? "原材料运输" : "Transport", num(transport),
                    num(share(transport, total)) + "%"}, td);
            addRow(t, new String[]{cn ? "生产制造" : "Manufacturing", num(make),
                    num(share(make, total)) + "%"}, td);
            addRow(t, new String[]{cn ? "合计" : "Total", num(total), "100.00%"}, td);
            doc.add(t);

            doc.add(section(cn ? "三、口径说明" : "3. Methodology", h2));
            doc.add(body(cn
                    ? "1. 核算边界为「摇篮到大门」或「摇篮到坟墓」，本报告绑定：" + f.getBoundary() + "。\n"
                      + "2. 碳足迹 = 原材料获取 + 原材料运输 + 生产制造，三段之和与总量差值不超过 0.01。\n"
                      + "3. 报告绑定生成时的足迹记录，后续修改不会改变本文件。"
                    : "1. Boundary: " + code(f.getBoundary(), "UNKNOWN") + ".\n"
                      + "2. Footprint = raw + transport + manufacturing (tolerance 0.01).\n"
                      + "3. Bound to the record snapshot; later edits do not alter this file.", body));
            doc.add(footer((cn ? "生成人：" : "Generated by: ") + generatedBy
                    + (cn ? "    状态：" : "    status: ") + f.getStatus(), small));
            Font mono = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
            if (machineLine) doc.add(machine("[MACHINE] type=footprint id=" + f.getId()
                    + " footprint=" + num(total)
                    + " boundary=" + code(f.getBoundary(), "UNKNOWN")
                    + " unit=" + code(f.getUnit(), "UNIT"), mono));
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /* ==================== 排版骨架 ==================== */

    /** 报告主标题：收紧行距，与下方信息行留出呼吸。 */
    private Paragraph title(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.35f);
        p.setSpacingAfter(4f);
        return p;
    }

    /** 抬头信息行。 */
    private Paragraph meta(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.5f);
        p.setSpacingAfter(2f);
        return p;
    }

    /** 章节标题：与上一张表留白，且自身与后续表格之间也留白，避免边框压字。 */
    private Paragraph section(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.4f);
        p.setSpacingBefore(SECTION_SPACING_BEFORE);
        p.setSpacingAfter(SECTION_SPACING_AFTER);
        return p;
    }

    /** 正文段落：统一行距，避免多行说明文字挤在一起。 */
    private Paragraph body(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.6f);
        p.setSpacingAfter(2f);
        return p;
    }

    /** 落款。 */
    private Paragraph footer(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.5f);
        p.setSpacingBefore(10f);
        return p;
    }

    /** 机器可读摘要。 */
    private Paragraph machine(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setLeading(f.getSize() * 1.5f);
        p.setSpacingBefore(3f);
        return p;
    }

    /** 表格工厂：统一上/下留白，跨页时重复表头且不在表头处断开。 */
    private PdfPTable table(float[] widths, float percent) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(percent);
        t.setSpacingBefore(TABLE_SPACING_BEFORE);
        t.setSpacingAfter(TABLE_SPACING_AFTER);
        t.setHeaderRows(1);
        t.setSplitLate(false);
        return t;
    }

    private void addHead(PdfPTable t, String[] cols, Font f, Color bg) {
        for (String c : cols) {
            PdfPCell cell = cell(c, f);
            cell.setBackgroundColor(bg);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(cell);
        }
    }

    private void addRow(PdfPTable t, String[] vals, Font f) {
        for (String v : vals) {
            String s = v == null ? "" : v;
            PdfPCell cell = cell(s, f);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            if (NUMERIC.matcher(s).matches()) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            t.addCell(cell);
        }
    }

    /**
     * 单元格内容用 Paragraph 承载，并在 cell 上显式指定行距。
     * Phrase 在 CJK 字体下换行高度只按单行计算，中文长文本折行后第二行会溢出，
     * 被下一行的边框压住；而 PdfPCell 会忽略段落自身的 leading，
     * 不显式设置时行距塌陷为字号大小，两行中文会挤在一起。
     */
    private PdfPCell cell(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setSpacingBefore(0f);
        p.setSpacingAfter(0f);
        PdfPCell cell = new PdfPCell(p);
        cell.setLeading(f.getSize() * 1.35f, 0f);
        cell.setPadding(CELL_PADDING);
        cell.setPaddingTop(CELL_PADDING + 1);
        cell.setPaddingBottom(CELL_PADDING + 1);
        return cell;
    }

    /* ==================== 数据格式化 ==================== */

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }

    /**
     * 机器可读摘要只允许 ASCII。中文枚举转固定代码，缺失时回退默认值，
     * 否则 HELVETICA 会静默丢弃中文字符（曾导致 boundary= 为空）。
     */
    private String code(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String t = raw.trim();
        if (ascii(t)) return t;
        if (t.contains("坟墓")) return "CRADLE_TO_GRAVE";
        if (t.contains("大门")) return "CRADLE_TO_GATE";
        if ("吨".equals(t)) return "t";
        if ("千克".equals(t)) return "kg";
        if ("克".equals(t)) return "g";
        if (t.contains("万立方米")) return "10k_m3";
        if (t.contains("立方米") || t.contains("m³")) return "m3";
        if (t.contains("万") && t.contains("件")) return "10k_pieces";
        if (t.contains("件")) return "piece";
        return fallback;
    }

    private boolean ascii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private Font font(BaseFont bf, int size, int style, Color color) {
        if (bf != null) {
            return color == null ? new Font(bf, size, style) : new Font(bf, size, style, color);
        }
        return color == null ? new Font(Font.HELVETICA, size, style)
                : new Font(Font.HELVETICA, size, style, color);
    }

    private String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
