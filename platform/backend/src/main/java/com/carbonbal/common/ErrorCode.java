package com.carbonbal.common;

/**
 * 业务错误码（字符串）。HTTP 状态由 {@link #http(String)} 统一映射。
 * 契约：只增不改；删除/复用视为破坏性变更。
 */
public final class ErrorCode {
    public static final String OK = "OK";

    /* 通用 */
    public static final String VALIDATION = "VALIDATION";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String REFERENCED = "REFERENCED";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String PERIOD_LOCKED = "PERIOD_LOCKED";
    public static final String FACTOR_MISSING = "FACTOR_MISSING";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
    public static final String INTERNAL = "INTERNAL";
    public static final String CSRF_INVALID = "CSRF_INVALID";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

    /* 认证与会话 */
    public static final String AUTH_BAD_CREDENTIAL = "AUTH_BAD_CREDENTIAL";
    public static final String AUTH_TOKEN_INVALID = "AUTH_TOKEN_INVALID";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String AUTH_CAPTCHA_INVALID = "AUTH_CAPTCHA_INVALID";
    public static final String AUTH_CODE_INVALID = "AUTH_CODE_INVALID";
    public static final String AUTH_CODE_EXPIRED = "AUTH_CODE_EXPIRED";
    public static final String AUTH_CODE_LIMIT = "AUTH_CODE_LIMIT";
    public static final String AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String PASSWORD_TEMP_EXPIRED = "PASSWORD_TEMP_EXPIRED";
    public static final String PASSWORD_NOT_SET = "PASSWORD_NOT_SET";

    /* 企业与成员 */
    public static final String TENANT_NOT_FOUND = "TENANT_NOT_FOUND";
    public static final String TENANT_SUSPENDED = "TENANT_SUSPENDED";
    public static final String TENANT_EXPIRED = "TENANT_EXPIRED";
    public static final String TENANT_PENDING = "TENANT_PENDING";
    public static final String TENANT_LICENSE_INVALID = "TENANT_LICENSE_INVALID";
    public static final String SEAT_LIMIT = "SEAT_LIMIT";

    /* 角色与页面 */
    public static final String PAGE_FORBIDDEN = "PAGE_FORBIDDEN";
    public static final String ROLE_LIMIT = "ROLE_LIMIT";
    public static final String ROLE_IN_USE = "ROLE_IN_USE";
    public static final String ROLE_DEFAULT = "ROLE_DEFAULT";
    public static final String MEMBER_PENDING = "MEMBER_PENDING";
    public static final String MEMBER_DISABLED = "MEMBER_DISABLED";
    public static final String MEMBER_LAST_ADMIN = "MEMBER_LAST_ADMIN";
    public static final String PHONE_TAKEN = "PHONE_TAKEN";
    public static final String SELF_OPERATION = "SELF_OPERATION";

    /* 业务规则 */
    public static final String SOD_VIOLATION = "SOD_VIOLATION";

    private ErrorCode() {}

    /** 错误码 → HTTP 状态。未登记的一律 400（新增码必须同步补这里与 docs/error-codes.md）。 */
    public static int http(String code) {
        if (code == null) return 400;
        return switch (code) {
            case UNAUTHORIZED, AUTH_BAD_CREDENTIAL, AUTH_TOKEN_INVALID, SESSION_REVOKED -> 401;
            case FORBIDDEN, MEMBER_PENDING, MEMBER_DISABLED,
                 TENANT_EXPIRED, TENANT_PENDING, CSRF_INVALID,
                 PAGE_FORBIDDEN -> 403;
            case NOT_FOUND, TENANT_NOT_FOUND -> 404;
            case METHOD_NOT_ALLOWED -> 405;
            case UNSUPPORTED_MEDIA_TYPE -> 415;
            case DUPLICATE, REFERENCED, VERSION_CONFLICT, PERIOD_LOCKED,
                 INSUFFICIENT, MEMBER_LAST_ADMIN, SELF_OPERATION, SOD_VIOLATION,
                 SEAT_LIMIT, ROLE_LIMIT, ROLE_IN_USE, ROLE_DEFAULT,
                 IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS -> 409;
            case FACTOR_MISSING, TENANT_LICENSE_INVALID -> 422;
            case ACCOUNT_DISABLED, ACCOUNT_LOCKED, PASSWORD_TEMP_EXPIRED, TENANT_SUSPENDED -> 423;
            case TOO_MANY_REQUESTS, AUTH_CODE_LIMIT, AUTH_RATE_LIMITED -> 429;
            case INTERNAL -> 500;
            default -> 400;
        };
    }
}
