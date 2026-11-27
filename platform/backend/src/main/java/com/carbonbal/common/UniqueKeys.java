package com.carbonbal.common;

/**
 * 软删与唯一索引的冲突处理。
 *
 * <p>业务表上的唯一索引（如 {@code uk_product_code}）不区分 {@code disabled}，
 * 记录软删后仍占着原编码/名称，用户删除后想用同一编码重建会被拒绝，
 * 且提示「已存在」却看不到那条记录，很难自查。
 * 删除时把唯一字段挪到带 id 的回收站命名空间，即可释放原值，同时保留审计可追溯。
 */
public final class UniqueKeys {

    private UniqueKeys() {
    }

    /**
     * 生成回收站键：{@code 原名#记录id}，按列长度截断原名部分，避免改完又超长。
     */
    public static String release(String value, Long id, int maxLen) {
        String suffix = "#" + id;
        String base = value == null ? "" : value;
        int keep = Math.max(0, maxLen - suffix.length());
        return (base.length() > keep ? base.substring(0, keep) : base) + suffix;
    }
}
