package com.carbonbal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P1-4：大请求体（超过内存阈值）走"流式摘要 + 临时文件 + 回放"路径的验证。
 *
 * <p>默认阈值是 1MB，用正常业务请求体永远触发不到这条分支，等于没测。这里把阈值压到 1KB，
 * 让常规产品创建请求体（约 600~800 字节）落在阈值两侧，从而真实验证：
 * <ul>
 *   <li>spool 路径下请求体仍能被控制器完整读取（业务正常创建）；</li>
 *   <li>同键重放返回首次结果，而非重复创建；</li>
 *   <li>临时文件用完即删，不留残留；</li>
 *   <li>超过 {@code max-body-bytes} 的请求直接 413，不进入业务。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.idempotency.max-inline-bytes=1024",
        "app.idempotency.max-body-bytes=4096"
})
class IdempotencySpoolTest extends ApiTestSupport {

    private Map<String, Object> product(String code, String remark) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "TST 落盘幂等");
        b.put("code", code);
        b.put("type", "一般产品");
        b.put("cls", "工业产品");
        b.put("unit", "吨");
        b.put("remark", remark);
        return b;
    }

    private long spoolFiles() throws Exception {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("idem-spool-")).count();
        }
    }

    @Test
    void spooledBodyStillCreatesAndReplays() throws Exception {
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        String code = "TST-SPOOL-" + System.currentTimeMillis();
        // 备注 400 字 → 请求体约 1.3KB，超过 1KB 的内存阈值，触发临时文件路径
        String remark = "落盘验证".repeat(100);
        assertThat(remark.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(1024);

        long before = spoolFiles();
        Resp first = call(post("/api/products").header("Idempotency-Key", key), token, product(code, remark));
        Resp second = call(post("/api/products").header("Idempotency-Key", key), token, product(code, remark));

        assertThat(first.http()).isEqualTo(200);
        assertThat(first.body().path("remark").asText()).isEqualTo(remark);
        assertThat(second.http()).isEqualTo(200);
        assertThat(second.body().path("id").asLong()).isEqualTo(first.body().path("id").asLong());

        Long count = jdbc.queryForObject("select count(*) from product where code = ?", Long.class, code);
        assertThat(count).isEqualTo(1L);
        // 临时文件已清理
        assertThat(spoolFiles()).isEqualTo(before);

        call(delete("/api/products/" + first.body().path("id").asLong()), token, null);
    }

    @Test
    void bodyBeyondCapIsRejected() throws Exception {
        String token = login("13800000001", "Admin@2026!Pass");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = product("TST-CAP-SPOOL-" + System.currentTimeMillis(), "z".repeat(6000));

        Resp r = call(post("/api/products").header("Idempotency-Key", key), token, body);

        assertThat(r.http()).isEqualTo(413);
        Long rows = jdbc.queryForObject("select count(*) from idempotency_record where idem_key = ?", Long.class, key);
        assertThat(rows).isZero();
    }
}
