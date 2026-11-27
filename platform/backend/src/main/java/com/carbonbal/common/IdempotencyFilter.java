package com.carbonbal.common;

import com.carbonbal.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 创建接口幂等：客户端带 {@code Idempotency-Key} 时，同一租户 + 同一键 24h 内只执行一次。
 *
 * <ul>
 *   <li>首次：占位行 → 执行业务 → 成功回写响应（2xx JSON），失败删除占位允许重试；</li>
 *   <li>重放：返回首次状态码与响应体，并加 {@code Idempotent-Replay: true}；</li>
 *   <li>同键不同请求：409 {@code IDEMPOTENCY_CONFLICT}；并发未完成：409 {@code IDEMPOTENCY_IN_PROGRESS}。</li>
 * </ul>
 *
 * <p>请求边界的处理（P1-4）：
 * <ol>
 *   <li>阈值配置化：{@code app.idempotency.max-inline-bytes}（默认 1MB，内存驻留上限）、
 *       {@code app.idempotency.max-body-bytes}（默认 10MB，硬上限）、{@code ttl-hours}；</li>
 *   <li>≤ 内存阈值：内存回放；</li>
 *   <li>&gt; 内存阈值：{@link IdempotencySupport#capture} 边读边 digest 边落临时文件，回放用
 *       {@code CachedFileRequest}，请求结束即删除临时文件，<b>不再</b> {@code readAllBytes()}；</li>
 *   <li>multipart <b>不再一律放行</b>：用表单字段 + 文件元数据 + 文件内容流式摘要生成指纹，
 *       只在占位失败时走重放/冲突分支，请求本身不包装（容器 parts 可复用）。</li>
 * </ol>
 *
 * <p>未带键、GET/PATCH/DELETE、认证接口仍直接放行，不影响既有客户端。
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    /** 幂等键长度上限（字符）。 */
    private static final int MAX_KEY = 128;

    private final JdbcTemplate jdbc;
    private final int maxInlineBytes;
    private final int maxBodyBytes;
    private final int maxResponseBytes;
    private final int ttlHours;

    /**
     * @param jdbc             幂等记录存储
     * @param maxInlineBytes   请求体内存驻留上限（字节），超过转临时文件
     * @param maxBodyBytes     请求体硬上限（字节），超过返回 413
     * @param maxResponseBytes 可回写缓存的响应体上限（字节），超过则不缓存响应体
     * @param ttlHours         幂等记录保留小时数
     */
    public IdempotencyFilter(JdbcTemplate jdbc,
                             @Value("${app.idempotency.max-inline-bytes:1048576}") int maxInlineBytes,
                             @Value("${app.idempotency.max-body-bytes:10485760}") int maxBodyBytes,
                             @Value("${app.idempotency.max-response-bytes:1048576}") int maxResponseBytes,
                             @Value("${app.idempotency.ttl-hours:24}") int ttlHours) {
        this.jdbc = jdbc;
        this.maxInlineBytes = Math.max(1024, maxInlineBytes);
        this.maxBodyBytes = Math.max(this.maxInlineBytes, maxBodyBytes);
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        this.ttlHours = Math.max(1, ttlHours);
    }

    /**
     * 只拦截"带幂等键的 POST /api/**"。
     *
     * <p>与旧实现的差异：multipart 不再无条件放行（旧实现在 {@code shouldNotFilter} 里直接返回 true，
     * 导致上传类接口完全没有重放保护）。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) return true;
        String uri = req.getRequestURI();
        if (uri == null || !uri.startsWith("/api/") || uri.startsWith("/api/auth/")) return true;
        String key = req.getHeader("Idempotency-Key");
        return key == null || key.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("Idempotency-Key").trim();
        if (key.length() > MAX_KEY) {
            writeError(res, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.VALIDATION, "幂等键过长（上限 128 字符）");
            return;
        }
        long contentLength = req.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            writeError(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.VALIDATION,
                    "请求体超过上限（" + maxBodyBytes + " 字节）");
            return;
        }

        if (isMultipart(req)) {
            handleMultipart(req, res, chain, key);
        } else {
            handleBody(req, res, chain, key);
        }
    }

    /* ==================== 普通（JSON/表单）请求 ==================== */

    private void handleBody(HttpServletRequest req, HttpServletResponse res, FilterChain chain, String key)
            throws ServletException, IOException {
        IdempotencySupport.Captured captured;
        try {
            captured = IdempotencySupport.capture(req, maxInlineBytes, maxBodyBytes);
        } catch (IdempotencySupport.PayloadTooLargeException e) {
            writeError(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.VALIDATION, e.getMessage());
            return;
        }
        try (captured) {
            execute(req, res, chain, key,
                    IdempotencySupport.canonical(req.getMethod(), req.getRequestURI(), captured.hash()),
                    captured.wrap(req));
        }
    }

    /* ==================== multipart 请求 ==================== */

    private void handleMultipart(HttpServletRequest req, HttpServletResponse res, FilterChain chain, String key)
            throws ServletException, IOException {
        String fingerprint;
        try {
            fingerprint = IdempotencySupport.multipartKey(req);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                writeError(res, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.VALIDATION,
                        "无法读取上传内容：" + io.getMessage());
                return;
            }
            throw e;
        }
        // 不包装请求：容器已缓存 parts，Spring 的 MultipartResolver 复用同一份解析结果
        execute(req, res, chain, key,
                IdempotencySupport.canonical(req.getMethod(), req.getRequestURI(), fingerprint), req);
    }

    /* ==================== 占位 / 执行 / 回写 ==================== */

    private void execute(HttpServletRequest req, HttpServletResponse res, FilterChain chain, String key,
                         String hash, HttpServletRequest downstream)
            throws ServletException, IOException {
        long orgId = CurrentUser.orgId();
        Long actorId = CurrentUser.userIdOrNull();
        String path = req.getRequestURI();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);

        try {
            jdbc.update("""
                    insert into idempotency_record
                      (org_id, actor_id, idem_key, method, path, request_hash, status_code, expires_at)
                    values (?, ?, ?, ?, ?, ?, 0, ?)
                    """, orgId, actorId, key, req.getMethod(), path, hash, expiresAt);
        } catch (DuplicateKeyException dup) {
            replayOrReject(res, orgId, key, hash);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(res);
        boolean success = false;
        try {
            chain.doFilter(downstream, wrapper);
            int status = wrapper.getStatus();
            byte[] out = wrapper.getContentAsByteArray();
            String ct = wrapper.getContentType();
            boolean json = ct != null && ct.toLowerCase().contains("json");
            if (status >= 200 && status < 300 && json && out.length <= maxResponseBytes) {
                jdbc.update("update idempotency_record set status_code=?, response_body=? where org_id=? and idem_key=?",
                        status, new String(out, StandardCharsets.UTF_8), orgId, key);
                success = true;
            }
        } finally {
            if (!success) {
                // 业务失败 / 非 JSON / 响应体过大：删除占位，允许客户端用同一键重试
                jdbc.update("delete from idempotency_record where org_id=? and idem_key=?", orgId, key);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void replayOrReject(HttpServletResponse res, long orgId, String key, String hash) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select request_hash, status_code, response_body from idempotency_record where org_id=? and idem_key=?",
                orgId, key);
        if (rows.isEmpty()) {
            // 占位行刚被回滚/清理，交给客户端重试
            writeError(res, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "相同请求正在处理，请稍后重试");
            return;
        }
        Map<String, Object> row = rows.get(0);
        String storedHash = String.valueOf(row.get("request_hash"));
        if (!storedHash.equals(hash)) {
            writeError(res, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键已用于不同请求");
            return;
        }
        int status = ((Number) row.get("status_code")).intValue();
        if (status == 0) {
            writeError(res, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "相同请求正在处理，请稍后重试");
            return;
        }
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Idempotent-Replay", "true");
        Object body = row.get("response_body");
        if (body != null) res.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /* ==================== 工具 ==================== */

    /** 判断是否 multipart：以 Content-Type 为准（容器/网关可能改写，故不用 instanceof）。 */
    private static boolean isMultipart(HttpServletRequest req) {
        String ct = req.getContentType();
        return ct != null && ct.toLowerCase().startsWith("multipart/");
    }

    private static void writeError(HttpServletResponse res, int status, String code, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"" + code + "\",\"body\":{\"message\":\"" + msg + "\"}}");
    }
}
