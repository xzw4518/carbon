package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 分析页筛选：年份 / 产品 / 供应商参数必须真正参与查询，不能只是"控件能点"。
 * 对应缺陷：碳足迹分析、供应链分析的筛选条件无任何效果。
 */
class AnalysisFilterTest extends ApiTestSupport {

    @Test
    void footprintFiltersByYear() throws Exception {
        String filler = login("filler", "Test@2026!Pass");

        Resp all = call(get("/api/analysis/footprint"), filler, null);
        assertThat(all.http()).isEqualTo(200);
        int total = all.body().path("table").path("rows").size();
        assertThat(total).isGreaterThan(0);

        Resp hit = call(get("/api/analysis/footprint").param("year", "2026"), filler, null);
        assertThat(hit.http()).isEqualTo(200);
        assertThat(hit.body().path("table").path("rows").size()).isEqualTo(total);

        Resp miss = call(get("/api/analysis/footprint").param("year", "2001"), filler, null);
        assertThat(miss.http()).isEqualTo(200);
        assertThat(miss.body().path("table").path("rows")).isEmpty();
        assertThat(miss.body().path("kpis")).isEmpty();
    }

    @Test
    void footprintFiltersByProductName() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp all = call(get("/api/analysis/footprint"), filler, null);
        String product = all.body().path("table").path("rows").get(0).path("product").asText();

        Resp hit = call(get("/api/analysis/footprint").param("product", product), filler, null);
        assertThat(hit.http()).isEqualTo(200);
        JsonNode rows = hit.body().path("table").path("rows");
        assertThat(rows.size()).isGreaterThan(0);
        rows.forEach(r -> assertThat(r.path("product").asText()).isEqualTo(product));
        assertThat(rows.size()).isLessThanOrEqualTo(all.body().path("table").path("rows").size());

        Resp miss = call(get("/api/analysis/footprint").param("product", "不存在的产品"), filler, null);
        assertThat(miss.http()).isEqualTo(200);
        assertThat(miss.body().path("table").path("rows")).isEmpty();
    }

    @Test
    void supplyChainFiltersBySupplierAndYear() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp all = call(get("/api/analysis/supply-chain"), filler, null);
        assertThat(all.http()).isEqualTo(200);
        JsonNode rows = all.body().path("table").path("rows");
        assertThat(rows.size()).isGreaterThan(1);

        // 选一级供应商时，结果应覆盖它自己以及挂在它下面的整条链
        String root = null;
        for (JsonNode r : rows) {
            if ("一级".equals(r.path("level").asText())) {
                root = r.path("supplier").asText();
                break;
            }
        }
        assertThat(root).as("演示数据应至少有一个一级供应商").isNotNull();
        Resp hit = call(get("/api/analysis/supply-chain").param("supplier", root), filler, null);
        assertThat(hit.http()).isEqualTo(200);
        JsonNode hitRows = hit.body().path("table").path("rows");
        assertThat(hitRows.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode r : hitRows) {
            // 子树成员的层级路径必然以根名称开头
            assertThat(r.path("path").asText())
                    .as("筛选 %s 返回了不属于该链路的行", root)
                    .startsWith(root);
        }

        // 选最末级供应商时，结果只应有它自己
        String leaf = null;
        int maxDepth = 0;
        for (JsonNode r : rows) {
            int depth = r.path("path").asText().split("›").length;
            if (depth > maxDepth) {
                maxDepth = depth;
                leaf = r.path("supplier").asText();
            }
        }
        if (leaf != null && maxDepth > 1) {
            Resp leafHit = call(get("/api/analysis/supply-chain").param("supplier", leaf), filler, null);
            assertThat(leafHit.http()).isEqualTo(200);
            JsonNode leafRows = leafHit.body().path("table").path("rows");
            assertThat(leafRows.size()).as("末级供应商不应带出其它行").isEqualTo(1);
            assertThat(leafRows.get(0).path("supplier").asText()).isEqualTo(leaf);
        }

        Resp miss = call(get("/api/analysis/supply-chain").param("year", "2001"), filler, null);
        assertThat(miss.http()).isEqualTo(200);
        assertThat(miss.body().path("table").path("rows")).isEmpty();
    }

    @Test
    void unfilteredEmptyDatasetStillReportsNotFound() throws Exception {
        // 不带任何筛选时，无数据应维持 404 语义（与"筛选无匹配"区分）
        String filler = login("filler", "Test@2026!Pass");
        Resp r = call(get("/api/analysis/supply-chain").param("supplier", " "), filler, null);
        assertThat(r.http()).isEqualTo(200);
        assertThat(r.body().path("table").path("rows").size()).isGreaterThan(0);
    }
}
