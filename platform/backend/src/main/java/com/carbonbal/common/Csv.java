package com.carbonbal.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** 轻量 CSV 生成：UTF-8 BOM（Excel 直接打开不乱码）+ RFC 4180 转义。 */
public final class Csv {

    /** 纯数字（含负数、科学计数）不转义，避免把 -1.5 变成文本。 */
    private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    private Csv() {}

    public static byte[] build(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        appendRow(sb, headers);
        for (List<Object> row : rows) appendRow(sb, row);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        // 公式注入防护：= + - @ 开头的单元格在 Excel 中会被当公式执行；单引号前缀可强制按文本显示
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !NUMERIC.matcher(s).matches()) {
            s = "'" + s;
        }
        if (s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
