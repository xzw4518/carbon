package com.carbonbal;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 断言 1、2、5、8：读数的写往返、审计留痕、版本冲突、越权。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadingApiTest extends ApiTestSupport {

    private static Long readingId;

    @Test
    @Order(1)
    void viewerCannotWrite() throws Exception {
        String viewer = limitedUser(java.util.List.of("home"));
        Resp r = call(post("/api/device-readings"), viewer,
                Map.of("deviceId", 1, "ts", "2026-11-25T10:00:00", "qty", 10,
                        "unit", "kWh", "rate", 0.001, "source", "手工补录"));
        assertThat(r.http()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("PAGE_FORBIDDEN");
    }

    @Test
    @Order(2)
    void createThenReadBackAndAudit() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp created = call(post("/api/device-readings"), filler, Map.of(
                "deviceId", 1, "ts", "2026-11-25T10:00:00", "qty", 100,
                "unit", "kWh", "rate", 0.001, "source", "手工补录",
                "clientRequestId", "it-001"));
        assertThat(created.http()).isEqualTo(200);
        readingId = created.body().path("id").asLong();
        assertThat(readingId).isPositive();

        Resp list = call(get("/api/device-readings").param("deviceId", "1"), filler, null);
        assertThat(list.http()).isEqualTo(200);
        boolean found = false;
        for (var node : list.body()) {
            if (node.path("id").asLong() == readingId) found = true;
        }
        assertThat(found).as("新增读数应能读回").isTrue();

        // 审计日志仅租户管理员可查，普通用户由 @PreAuthorize 拒绝
        assertThat(call(get("/api/audit-logs"), filler, null).http()).isEqualTo(403);
        String admin = login("admin", "Admin@2026!Pass");
        Resp audit = call(get("/api/audit-logs")
                .param("entity", "device_reading")
                .param("entityId", String.valueOf(readingId)), admin, null);
        assertThat(audit.body().path("rows").size()).isGreaterThanOrEqualTo(1);
        assertThat(audit.body().path("rows").get(0).path("action").asText()).isEqualTo("create");
    }

    @Test
    @Order(3)
    void idempotentByClientRequestId() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp again = call(post("/api/device-readings"), filler, Map.of(
                "deviceId", 1, "ts", "2026-11-25T10:00:00", "qty", 999,
                "unit", "kWh", "rate", 0.001, "source", "手工补录",
                "clientRequestId", "it-001"));
        assertThat(again.http()).isEqualTo(200);
        assertThat(again.body().path("id").asLong()).isEqualTo(readingId);
        assertThat(again.body().path("qty").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    @Order(4)
    void updateWritesAuditAndRejectsStaleVersion() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp updated = call(put("/api/device-readings/" + readingId), filler,
                Map.of("version", 0, "qty", 200));
        assertThat(updated.http()).isEqualTo(200);
        assertThat(updated.body().path("version").asInt()).isEqualTo(1);

        Resp stale = call(put("/api/device-readings/" + readingId), filler,
                Map.of("version", 0, "qty", 300));
        assertThat(stale.http()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(stale.body().path("detail").path("currentVersion").asInt())
                .as("冲突时返回最新版本，客户端据此刷新").isEqualTo(1);

        String auditAdmin = login("admin", "Admin@2026!Pass");
        Resp audit = call(get("/api/audit-logs")
                .param("entity", "device_reading")
                .param("entityId", String.valueOf(readingId)), auditAdmin, null);
        boolean hasUpdate = java.util.stream.StreamSupport
                .stream(audit.body().path("rows").spliterator(), false)
                .anyMatch(n -> "update".equals(n.path("action").asText())
                        && n.path("beforeJson").asText().contains("100")
                        && n.path("afterJson").asText().contains("200"));
        assertThat(hasUpdate).as("审计需保留改前 100 与改后 200").isTrue();
    }

    @Test
    @Order(5)
    void csvImportReportsLineErrors() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String csv = "deviceCode,ts,qty,unit,rate,source\n"
                + "EQ-A101,2026-11-26 08:00,120,kWh,0.001,手工补录\n"
                + "EQ-NOPE,2026-11-26 08:00,10,kWh,0.001,手工补录\n";
        MvcResultHolder holder = new MvcResultHolder();
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/device-readings/import")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "r.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + filler))
                .andReturn();
        holder.text = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        var node = json.readTree(holder.text).path("body");
        assertThat(node.path("success").asInt()).isEqualTo(1);
        assertThat(node.path("failed").asInt()).isEqualTo(1);
        assertThat(node.path("errors").get(0).path("reason").asText()).contains("设备编码不存在");
    }

    private static class MvcResultHolder {
        String text;
    }
}
