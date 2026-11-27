package com.carbonbal;

import com.carbonbal.accounting.AccountingDtos;
import com.carbonbal.footprint.ProductFootprint;
import com.carbonbal.report.PdfBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 排版回归：锁住三类曾被客户发现的遮挡问题。
 * 1. 章节标题被紧随其后的表格上边框/表头底色压住；
 * 2. 单元格长中文折行后行高只按单行计算，第二行溢出压字；
 * 3. 机器可读摘要中的中文枚举被英文字体静默丢弃（boundary= 为空）。
 */
class PdfLayoutTest {

    private static final String ORG = "华东精密制造有限公司";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 逐字提取文字坐标，按 y 聚成行。 */
    private record Row(float y, float x, float w, float h, String text) {}

    private List<Row> rows(byte[] pdf) throws IOException {
        List<Row> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        String u = p.getUnicode();
                        if (u == null || u.isBlank()) continue;
                        out.add(new Row(p.getYDirAdj(), p.getXDirAdj(), p.getWidthDirAdj(),
                                p.getHeightDir(), u));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        // 按 y 归类：同一基线的字符合成一行
        List<Row> lines = new ArrayList<>();
        List<Row> sorted = out.stream().sorted(Comparator.comparingDouble(Row::y)).toList();
        for (Row r : sorted) {
            if (!lines.isEmpty() && Math.abs(lines.get(lines.size() - 1).y() - r.y()) < 1.5f) {
                Row last = lines.remove(lines.size() - 1);
                lines.add(new Row(last.y(), last.x(), last.w(), last.h(), last.text() + r.text()));
            } else {
                lines.add(r);
            }
        }
        return lines;
    }

    private AccountingDtos.LineView line(int i, String device) {
        return new AccountingDtos.LineView((long) i, (long) i, (long) i, device, 1L, "v1",
                i % 3 == 0 ? "scope1" : "scope2", new BigDecimal("51.2000"), "MWh",
                new BigDecimal("0.5617"), new BigDecimal("28.7590"));
    }

    private AccountingDtos.PeriodView period(List<AccountingDtos.LineView> lines) {
        return new AccountingDtos.PeriodView(1L, "2026-11", "submitted",
                new BigDecimal("31.9337"), new BigDecimal("57.9909"), new BigDecimal("89.9246"),
                114, 1, null, null, null, null, null, null, null, null, lines, List.of(), false);
    }

    @Test
    @DisplayName("章节标题与表格之间保留足够间距，标题不被上边框压住")
    void sectionTitleIsNotCoveredByTable() throws Exception {
        List<AccountingDtos.LineView> lines = List.of(
                line(1, "空压机 A1"), line(2, "数控车床 L3"), line(3, "燃气加热炉"));
        byte[] pdf = new PdfBuilder().build(period(lines), ORG, "13800000001");
        List<Row> rows = rows(pdf);

        for (String title : List.of("一、核算结果", "二、核算明细", "三、口径说明")) {
            Row t = rows.stream().filter(r -> r.text().startsWith(title)).findFirst()
                    .orElseThrow(() -> new AssertionError("缺少章节标题：" + title));
            float bottom = t.y() + t.h();
            Row next = rows.stream().filter(r -> r.y() > bottom).findFirst()
                    .orElseThrow(() -> new AssertionError("标题后无内容：" + title));
            // 曾被压到 5.6pt：表格上边框正好落在标题字形下缘
            assertThat(next.y() - bottom)
                    .as("「%s」与其后内容的垂直间隙（pt）", title)
                    .isGreaterThanOrEqualTo(8f);
        }
    }

    @Test
    @DisplayName("长中文折行后行高随行数增长，第二行不溢出压到下一行")
    void longTextWrapsWithGrowingRowHeight() throws Exception {
        String longName = "空压机组 18 号（主车间东侧一号压缩空气站）";
        List<AccountingDtos.LineView> lines = List.of(line(1, longName), line(2, "短名"));
        byte[] pdf = new PdfBuilder().build(period(lines), ORG, "13800000001");
        List<Row> rows = rows(pdf);

        // 设备列位于表格左半部分，按 x 过滤出设备名文字
        List<Row> devices = rows.stream()
                .filter(r -> r.x() >= 47f && r.x() < 190f)
                .filter(r -> r.text().contains("空压机") || r.text().contains("号")
                        || r.text().contains("短名"))
                .toList();
        assertThat(devices).as("设备名应折成多行").hasSizeGreaterThanOrEqualTo(2);

        float inner = devices.get(1).y() - devices.get(0).y();   // 同一单元格内的行距
        Row shortLine = rows.stream().filter(r -> r.text().contains("短名")).findFirst()
                .orElseThrow(() -> new AssertionError("缺少第二行数据"));
        float cross = shortLine.y() - devices.get(1).y();        // 跨行的间距

        // 行高被按单行计算时，跨行间距会塌陷到与单元格内行距相同
        assertThat(inner).as("单元格内行距（pt）").isGreaterThanOrEqualTo(11f);
        assertThat(cross).as("跨行间距应明显大于单元格内行距（pt）").isGreaterThan(inner + 4f);
    }

    @Test
    @DisplayName("机器可读摘要保留边界代码，不因英文字体丢失中文")
    void machineLineKeepsAsciiBoundaryCode() throws Exception {
        ProductFootprint f = new ProductFootprint();
        f.setId(1L);
        f.setName("精密轴承座 A 型");
        f.setUnit("吨");
        f.setQty(new BigDecimal("5200"));
        f.setFootprint(new BigDecimal("1.8420"));
        f.setDate(LocalDate.of(2026, 11, 12));
        f.setBoundary("摇篮到大门");
        f.setStageRaw(new BigDecimal("1.1210"));
        f.setStageTransport(new BigDecimal("0.2010"));
        f.setStageMake(new BigDecimal("0.5200"));
        f.setStatus("已核算");

        byte[] pdf = new PdfBuilder().buildFootprint(f, ORG, "13800000001");
        String text = rows(pdf).stream().map(Row::text).reduce("", String::concat);

        assertThat(text).contains("boundary=CRADLE_TO_GATE");
        assertThat(text).contains("unit=t");
        // 机器行必须保持纯 ASCII，便于第三方解析
        String machine = text.substring(text.indexOf("[MACHINE]"));
        assertThat(machine.chars().allMatch(c -> c < 128)).isTrue();
    }
}
