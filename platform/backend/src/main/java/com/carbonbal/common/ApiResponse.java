package com.carbonbal.common;

import java.util.Map;

/** 统一信封：status=0 成功；失败为字符串错误码，HTTP 状态由 ErrorCode.http 映射。 */
public record ApiResponse(Object status, Object body) {
    public static ApiResponse ok(Object body) {
        return new ApiResponse(0, body);
    }

    public static ApiResponse error(String code, String message, Object detail) {
        return new ApiResponse(code, detail == null
                ? Map.of("message", message)
                : Map.of("message", message, "detail", detail));
    }
}
