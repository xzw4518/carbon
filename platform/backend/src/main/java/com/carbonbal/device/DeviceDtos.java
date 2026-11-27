package com.carbonbal.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 设备域 DTO：本类是<b>设备列定义的唯一真相源</b>。
 *
 * <p>TODO(v1.1 单一来源)：设备表的列顺序/列名在以下两处各有一份副本，跨语言（Java ↔ JS）统一
 * 需要构建期代码生成，本轮不做：
 * <ul>
 *   <li>前端设备列表表头：{@code carbonbal-web/assets/pages-core.js}（搜索 "设备列定义" 可见标注）；</li>
 *   <li>设备导入/导出表头：本包的 CSV 处理代码。</li>
 * </ul>
 * 约束：改动本类的字段顺序或命名时，必须同步前端表头，否则导入模板与页面会错位。
 * 口径登记见 {@code carbonbal-web/docs/字段与口径.md} 的「设备列定义」一行。
 */
public class DeviceDtos {
    public record DeviceReq(
            @NotBlank(message = "设备名称必填") String name,
            @NotBlank(message = "设备编码必填") String code,
            @NotBlank(message = "能源种类必填") String energyType,
            String unit,
            String status,
            Long facilityId,
            String deviceType,
            String level,
            String remark,
            Integer version) {}

    public record DeviceView(
            Long id, String name, String code, String energyType, String unit, String status,
            Long facilityId, String facilityName, String deviceType, String level, String remark,
            Integer version, Boolean disabled) {}

    public record ImportResult(int success, int failed, List<Map<String, Object>> errors) {}
}
