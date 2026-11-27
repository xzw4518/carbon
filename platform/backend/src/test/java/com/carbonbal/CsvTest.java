package com.carbonbal;

import com.carbonbal.common.Csv;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CSV 导出必须防公式注入，同时不能破坏负数与普通文本。 */
class CsvTest {

    private String build(List<List<Object>> rows) {
        return new String(Csv.build(List.of("名称", "值"), rows), StandardCharsets.UTF_8);
    }

    @Test
    void formulaCellsAreNeutralized() {
        String out = build(List.of(
                List.of("=1+1", 1),
                List.of("+SUM(A1:A2)", 2),
                List.of("@cmd", 3)));
        assertThat(out).contains("'=1+1").contains("'+SUM(A1:A2)").contains("'@cmd");
    }

    @Test
    void negativeNumbersStayNumeric() {
        String out = build(List.of(List.of("温度", -1.5), List.of("科学计数", "-2e3")));
        assertThat(out).contains(",-1.5").contains("-2e3");
        assertThat(out).doesNotContain("'-1.5").doesNotContain("'-2e3");
    }

    @Test
    void commaAndQuoteStillEscaped() {
        String out = build(List.of(List.of("a,b\"c", 1)));
        assertThat(out).contains("\"a,b\"\"c\"");
    }
}
