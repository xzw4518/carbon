package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** 数据入口与审计筛选的防呆：导入必须先过表头，日期筛选不能静默忽略。 */
class DataGuardTest extends ApiTestSupport {

    @Test
    void importRejectsWrongHeaderAndWritesNothing() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        int before = call(get("/api/products?size=1"), admin, null).body().path("total").asInt();

        MockMultipartFile bad = new MockMultipartFile("file", "bad.csv", "text/csv",
                "姓名,编码\nfoo,bar\n".getBytes(StandardCharsets.UTF_8));
        String text = mvc.perform(multipart("/api/import?resource=products").file(bad)
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode node = json.readTree(text);
        assertThat(node.path("status").asText()).as("错误表头应 400 VALIDATION").isEqualTo("VALIDATION");

        int after = call(get("/api/products?size=1"), admin, null).body().path("total").asInt();
        assertThat(after).as("错误文件不得写入任何数据").isEqualTo(before);
    }

    @Test
    void auditDateFilterRejectsGarbage() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        Resp r = call(get("/api/audit-logs?from=abc"), admin, null);
        assertThat(r.http()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION");
    }

    @Test
    void auditDateFilterAcceptsIsoDateTime() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        Resp r = call(get("/api/audit-logs?from=2026-11-01T00:00:00"), admin, null);
        assertThat(r.http()).isEqualTo(200);
    }
}
