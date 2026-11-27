package com.carbonbal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M05：创建接口幂等键（Idempotency-Key）行为。
 *
 * <p>覆盖 P1-4 的请求边界：multipart 不再放行（同键重放只落一条、同键异体 409）、
 * 大于内存阈值的请求体走"流式摘要 + 临时文件"路径且不留残留临时文件。
 */
class IdempotencyTest extends ApiTestSupport {

    private static final String FILE_NAME = "TST-idem-upload.txt";

    private Map<String, Object> product(String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "TST 幂等验证");
        b.put("code", code);
        b.put("type", "一般产品");
        b.put("cls", "工业产品");
        b.put("unit", "吨");
        return b;
    }

    private int countByKeyword(String token, String kw) throws Exception {
        Resp r = call(get("/api/products").param("keyword", kw).param("size", "50"), token, null);
        return r.body().path("total").asInt();
    }

    /** 原始执行：需要断言响应头（幂等重放头）时使用。 */
    private MvcResult perform(MockHttpServletRequestBuilder req, String token) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn();
    }

    /** multipart 上传（附件接口要求 evidence.list 页面权限，管理员可用）。 */
    private MockHttpServletRequestBuilder upload(String key, String fileName, byte[] content) {
        MockMultipartFile f = new MockMultipartFile("file", fileName, "text/plain", content);
        MockHttpServletRequestBuilder b = multipart("/api/files").file(f).param("bizType", "evidence");
        if (key != null) b.header("Idempotency-Key", key);
        return b;
    }

    /** 清掉本次测试写下的文件行与磁盘文件（附件写在 app.file.root 下，不清理会累积）。 */
    private void cleanupUploads() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, storage_key from file_object where name = ?", FILE_NAME);
        for (Map<String, Object> row : rows) {
            Object key = row.get("storage_key");
            if (key != null) {
                try {
                    Files.deleteIfExists(Paths.get("./data/files", key.toString()).toAbsolutePath().normalize());
                } catch (Exception ignored) {
                    // 磁盘清理失败不影响断言，DB 行必须清掉
                }
            }
        }
        jdbc.update("delete from file_object where name = ?", FILE_NAME);
        jdbc.update("delete from audit_log where entity = 'file_object' and after_json like ?", "%" + FILE_NAME + "%");
    }

    /** 临时目录里本工具产生的 spool 文件数（用于断言"用完即删"）。 */
    private long spoolFiles() throws Exception {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("idem-spool-")).count();
        }
    }

    @Test
    void sameKeyAndBodyReplaysFirstResult() throws Exception {
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        String code = "TST-IDEM-" + System.currentTimeMillis();

        Resp first = call(post("/api/products").header("Idempotency-Key", key), token, product(code));
        Resp second = call(post("/api/products").header("Idempotency-Key", key), token, product(code));

        assertThat(first.http()).isEqualTo(200);
        assertThat(second.http()).isEqualTo(200);
        assertThat(second.body().path("id").asLong()).isEqualTo(first.body().path("id").asLong());
        assertThat(countByKeyword(token, code)).isEqualTo(1);

        call(delete("/api/products/" + first.body().path("id").asLong()), token, null);
    }

    @Test
    void sameKeyDifferentBodyConflicts() throws Exception {
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        String code1 = "TST-IDEM-A-" + System.currentTimeMillis();
        String code2 = "TST-IDEM-B-" + System.currentTimeMillis();

        Resp first = call(post("/api/products").header("Idempotency-Key", key), token, product(code1));
        Resp conflict = call(post("/api/products").header("Idempotency-Key", key), token, product(code2));

        assertThat(first.http()).isEqualTo(200);
        assertThat(conflict.http()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(countByKeyword(token, code2)).isZero();

        call(delete("/api/products/" + first.body().path("id").asLong()), token, null);
    }

    @Test
    void withoutKeyStillCreatesTwice() throws Exception {
        String token = login("13800000001", "Admin@2026!Pass");
        String code = "TST-NOKEY-" + System.currentTimeMillis();

        Resp first = call(post("/api/products"), token, product(code + "-1"));
        Resp second = call(post("/api/products"), token, product(code + "-2"));

        assertThat(first.http()).isEqualTo(200);
        assertThat(second.http()).isEqualTo(200);
        assertThat(second.body().path("id").asLong()).isNotEqualTo(first.body().path("id").asLong());
        assertThat(countByKeyword(token, code)).isEqualTo(2);

        call(delete("/api/products/" + first.body().path("id").asLong()), token, null);
        call(delete("/api/products/" + second.body().path("id").asLong()), token, null);
    }

    /* ==================== P1-4：multipart 请求边界 ==================== */

    @Test
    void multipartSameKeyReplaysAndStoresSingleFile() throws Exception {
        cleanupUploads();
        long spoolBefore = spoolFiles();
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        byte[] content = "TST 幂等上传内容：同一键只应落一条".getBytes(StandardCharsets.UTF_8);

        MvcResult first = perform(upload(key, FILE_NAME, content), token);
        MvcResult second = perform(upload(key, FILE_NAME, content), token);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));

        Long rows = jdbc.queryForObject("select count(*) from file_object where name = ?", Long.class, FILE_NAME);
        assertThat(rows).isEqualTo(1L);
        // 上传走的是 multipart 指纹路径，不落临时文件
        assertThat(spoolFiles()).isEqualTo(spoolBefore);
        cleanupUploads();
    }

    @Test
    void multipartSameKeyDifferentContentConflicts() throws Exception {
        cleanupUploads();
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();

        MvcResult first = perform(upload(key, FILE_NAME, "内容 A".getBytes(StandardCharsets.UTF_8)), token);
        MvcResult conflict = perform(upload(key, FILE_NAME, "内容 B".getBytes(StandardCharsets.UTF_8)), token);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("IDEMPOTENCY_CONFLICT");
        Long rows = jdbc.queryForObject("select count(*) from file_object where name = ?", Long.class, FILE_NAME);
        assertThat(rows).isEqualTo(1L);
        cleanupUploads();
    }

    /* ==================== P1-4：超大 JSON 请求体 ==================== */

    @Test
    void oversizedJsonBodyDoesNotLeakTempFilesAndRejectsConsistently() throws Exception {
        long spoolBefore = spoolFiles();
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();

        // 300KB 请求体：默认阈值 1MB，走内存路径；显式超过 256KB 旧阈值，验证不再"静默放行且不占位"
        String huge = "x".repeat(300 * 1024);
        Map<String, Object> body = product("TST-BIG-" + System.currentTimeMillis());
        body.put("name", huge);

        Resp first = call(post("/api/products").header("Idempotency-Key", key), token, body);
        Resp second = call(post("/api/products").header("Idempotency-Key", key), token, body);

        // 业务校验（名称 ≤120 字）必然 400：两次结果一致，且不产生 500 / 不占位
        assertThat(first.http()).isEqualTo(400);
        assertThat(second.http()).isEqualTo(400);
        assertThat(second.code()).isEqualTo(first.code());
        Long placeholders = jdbc.queryForObject(
                "select count(*) from idempotency_record where idem_key = ?", Long.class, key);
        assertThat(placeholders).isZero();
        assertThat(spoolFiles()).isEqualTo(spoolBefore);
    }

    @Test
    void requestBeyondBodyCapIsRejectedWithoutBuffering() throws Exception {
        // 通过一次带超大 Content-Length 的请求验证快速拒绝分支（不进入业务、不占位）
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = product("TST-CAP-" + System.currentTimeMillis());
        body.put("name", "y".repeat(11 * 1024 * 1024));

        Resp r = call(post("/api/products").header("Idempotency-Key", key), token, body);
        assertThat(r.http()).isEqualTo(413);
        Long rows = jdbc.queryForObject("select count(*) from idempotency_record where idem_key = ?", Long.class, key);
        assertThat(rows).isZero();
    }

    /** 便于本地排查：列出 spool 目录内容（仅调试用，不参与断言）。 */
    @SuppressWarnings("unused")
    private static String describeSpool() throws Exception {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmp)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.joining(","));
        }
    }
}
