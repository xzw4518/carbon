package com.carbonbal.common;

import com.carbonbal.device.DeviceReading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 能源折标煤换算（tce）。活动数据 = 读数 × 换算率，单位与因子单位一致：
 * 电力 MWh、天然气 万Nm3、柴油/汽油 吨、蒸汽 GJ。
 * 系数取国家统计口径（与原型展示一致），仅用于分析与能源报告，不参与碳核算。
 */
public final class EnergyMath {
    private EnergyMath() {}

    public record Conversion(String unit, BigDecimal tcePerUnit) {}

    private static final Map<String, Conversion> MAP = Map.of(
            "外购电力", new Conversion("MWh", new BigDecimal("0.1229")),
            "天然气", new Conversion("万Nm3", new BigDecimal("11.0000")),
            "柴油", new Conversion("吨", new BigDecimal("1.4571")),
            "汽油", new Conversion("吨", new BigDecimal("1.4714")),
            "外购蒸汽", new Conversion("GJ", new BigDecimal("0.0341"))
    );

    public static boolean supported(String fuel) {
        return MAP.containsKey(fuel);
    }

    public static String unit(String fuel) {
        Conversion c = MAP.get(fuel);
        return c == null ? "" : c.unit();
    }

    public static BigDecimal activity(DeviceReading r) {
        BigDecimal qty = r.getQty() == null ? BigDecimal.ZERO : r.getQty();
        BigDecimal rate = r.getRate() == null ? BigDecimal.ONE : r.getRate();
        return qty.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal tce(String fuel, BigDecimal activity) {
        Conversion c = MAP.get(fuel);
        if (c == null || activity == null) return BigDecimal.ZERO;
        return activity.multiply(c.tcePerUnit()).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal tce(DeviceReading r, String fuel) {
        return tce(fuel, activity(r));
    }

    public static BigDecimal ratio(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }
}
