package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 导出文件的手机号口径：必须输出完整号码，好让导出文件可直接回导。
 * <p>
 * 与列表接口（{@code GET /api/users}）刻意分开验证：列表页仍返回脱敏手机号
 * （前端依赖该字段展示），导出走的是另一条按 userId 回查 {@code app_user.phone} 的路径。
 * <p>
 * 本类不创建任何成员（回导的每一行都应因号码已存在而失败），因此与席位占用、
 * 方法执行顺序无关，也不影响其它用例的数据。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserExportPhoneTest extends ApiTestSupport {

    private static final String ADMIN_PHONE = "13800000001";
    private static final String ADMIN_PWD = "Admin@2026!Pass";

    /** 完整手机号的正则：11 位、以 1 开头；脱敏值（138****0001）必然不匹配。 */
    private static final String FULL_PHONE = "^1\\d{10}$";

    @Test
    void exportCsvKeepsFullPhoneAndCanBeReimported() throws Exception {
        String admin = login(ADMIN_PHONE, ADMIN_PWD);

        byte[] exported = mvc.perform(get("/api/users/export").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(exported, StandardCharsets.UTF_8);

        // 列表接口仍返回脱敏值（前端依赖），导出文件不得复用该字段
        JsonNode members = call(get("/api/users"), admin, null).body();
        boolean maskedInList = false;
        for (JsonNode n : members) {
            if (n.path("phone").asText().contains("****")) maskedInList = true;
        }
        assertThat(maskedInList).as("列表页手机号仍应脱敏").isTrue();

        // 导出首列：整份文件不含 ****，且每行首列都是完整 11 位号码
        assertThat(csv).as("导出文件不应出现脱敏手机号").doesNotContain("****");
        String[] lines = csv.split("\r?\n");
        int dataRows = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            dataRows++;
            String firstCell = line.split(",", -1)[0];
            assertThat(firstCell).as("导出第 " + (i + 1) + " 行手机号应为完整 11 位号码: " + line)
                    .matches(FULL_PHONE);
        }
        assertThat(dataRows).as("导出应有成员数据行").isPositive();

        // 同一份文件直接回导：每行都因号码已存在失败（不是格式错误），success 为 0
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", exported);
        Resp r = call(multipart("/api/users/import").file(file), admin, null);
        assertThat(r.http()).as("回导响应: " + r.body()).isEqualTo(200);
        assertThat(r.body().path("success").asInt()).as("导出文件中的成员均已存在，不应重复创建").isZero();
        assertThat(r.body().path("failed").asInt()).as("每一行都应按已存在失败").isPositive();
        JsonNode errors = r.body().path("errors");
        assertThat(errors.size()).isEqualTo(r.body().path("failed").asInt());
        for (JsonNode err : errors) {
            String msg = err.path("message").asText();
            assertThat(msg).as("回导错误应为已存在: " + err).contains("已是本企业成员");
            assertThat(msg).as("完整号码回导不应报格式错误: " + err).doesNotContain("手机号格式不正确");
        }
    }
}
