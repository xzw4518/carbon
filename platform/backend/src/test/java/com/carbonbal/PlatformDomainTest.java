package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 主数据 / 资产 / 交易 / 足迹的读写、唯一约束、乐观锁、超卖与口径校验。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformDomainTest extends ApiTestSupport {

    private static Long productId;
    private static Long tradeId;
    private static java.math.BigDecimal assetQtyBefore;

    @Test
    @Order(1)
    void productListAndCreate() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/products").param("page", "1").param("size", "10"), filler, null);
        assertThat(list.http()).isEqualTo(200);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(5);

        Resp created = call(post("/api/products"), filler, Map.of(
                "name", "测试支架 X", "code", "TST-X01", "model", "X-1",
                "type", "一般产品", "cls", "工业产品", "unit", "吨",
                "l1", 0.40, "l2", 0.36, "l3", 0.33));
        assertThat(created.http()).isEqualTo(200);
        productId = created.body().path("id").asLong();

        Resp dup = call(post("/api/products"), filler, Map.of(
                "name", "重复编码", "code", "TST-X01",
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(dup.http()).as("同企业内产品编码唯一").isEqualTo(409);
    }

    @Test
    @Order(2)
    void productUpdateUsesOptimisticLock() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp ok = call(put("/api/products/" + productId), filler, Map.of(
                "name", "测试支架 X 改", "code", "TST-X01", "model", "X-2",
                "type", "低碳产品", "cls", "工业产品", "unit", "吨", "version", 0));
        assertThat(ok.http()).isEqualTo(200);
        assertThat(ok.body().path("version").asInt()).isEqualTo(1);

        Resp stale = call(put("/api/products/" + productId), filler, Map.of(
                "name", "并发覆盖", "code", "TST-X01",
                "type", "低碳产品", "cls", "工业产品", "unit", "吨", "version", 0));
        assertThat(stale.http()).isEqualTo(409);
        assertThat(stale.body().path("detail").path("currentVersion").asInt()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void viewerCannotWriteMasterData() throws Exception {
        String viewer = limitedUser(java.util.List.of("home"));
        Resp r = call(post("/api/products"), viewer, Map.of(
                "name", "越权产品", "code", "TST-V01",
                "type", "一般产品", "cls", "工业产品", "unit", "吨"));
        assertThat(r.http()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("PAGE_FORBIDDEN");
    }

    @Test
    @Order(4)
    void supplierListAndCreate() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/suppliers").param("page", "1").param("size", "10"), filler, null);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(4);

        Resp created = call(post("/api/suppliers"), filler, Map.of(
                "name", "测试供应商", "product", "精密轴承座 A 型", "unit", "吨",
                "fpUnit", "tCO2e/t", "footprint", 1.23, "date", "2026-11-20",
                "target", "范围三", "level", "二级", "sort", 9));
        assertThat(created.http()).isEqualTo(200);
        assertThat(created.body().path("footprint").decimalValue()).isEqualByComparingTo("1.23");
    }

    @Test
    @Order(5)
    void tradeSellDeductsAndBlocksOversell() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp assets = call(get("/api/carbon-assets").param("keyword", "碳配额"), filler, null);
        JsonNode asset = assets.body().path("rows").get(0);
        long assetId = asset.path("id").asLong();
        assetQtyBefore = asset.path("qty").decimalValue();

        Resp sold = call(post("/api/carbon-trades"), filler, Map.of(
                "assetId", assetId, "type", "售出", "qty", 100, "price", 96.5,
                "party", "全国碳市场", "usePurpose", "盈余处置", "tradeDate", "2026-11-24"));
        assertThat(sold.http()).isEqualTo(200);
        tradeId = sold.body().path("id").asLong();
        assertThat(sold.body().path("amount").decimalValue()).isEqualByComparingTo("9650");

        Resp after = call(get("/api/carbon-assets").param("keyword", "碳配额"), filler, null);
        java.math.BigDecimal qtyAfter = after.body().path("rows").get(0).path("qty").decimalValue();
        assertThat(assetQtyBefore.subtract(qtyAfter)).isEqualByComparingTo("100");

        Resp oversell = call(post("/api/carbon-trades"), filler, Map.of(
                "assetId", assetId, "type", "售出", "qty", 999999, "price", 96.5,
                "tradeDate", "2026-11-24"));
        assertThat(oversell.http()).as("超卖必须拒绝").isEqualTo(409);
    }

    @Test
    @Order(6)
    void deleteTradeRestoresStock() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        Resp del = call(delete("/api/carbon-trades/" + tradeId), admin, null);
        assertThat(del.http()).isEqualTo(200);

        Resp after = call(get("/api/carbon-assets").param("keyword", "碳配额"), admin, null);
        java.math.BigDecimal qty = after.body().path("rows").get(0).path("qty").decimalValue();
        assertThat(qty).as("删除售出交易应冲销回库存").isEqualByComparingTo(assetQtyBefore);
    }

    @Test
    @Order(7)
    void footprintStageSumMustMatch() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp bad = call(post("/api/footprints"), filler, Map.of(
                "name", "阶段不匹配产品", "unit", "t", "qty", 1000,
                "footprint", 2.0, "date", "2026-11-24", "boundary", "摇篮到大门",
                "stageRaw", 0.5, "stageTransport", 0.3, "stageMake", 0.4));
        assertThat(bad.http()).as("阶段之和 1.2 ≠ 2.0 应被拒绝").isEqualTo(400);

        Resp badBoundary = call(post("/api/footprints"), filler, Map.of(
                "name", "边界非法", "unit", "t", "qty", 1000,
                "footprint", 1.2, "date", "2026-11-24", "boundary", "随便写",
                "stageRaw", 0.5, "stageTransport", 0.3, "stageMake", 0.4));
        assertThat(badBoundary.http()).isEqualTo(400);

        Resp ok = call(post("/api/footprints"), filler, Map.of(
                "name", "阶段匹配产品", "unit", "t", "qty", 1000,
                "footprint", 1.2, "date", "2026-11-24", "boundary", "摇篮到大门",
                "stageRaw", 0.5, "stageTransport", 0.3, "stageMake", 0.4));
        assertThat(ok.http()).isEqualTo(200);
        assertThat(ok.body().path("footprint").decimalValue()).isEqualByComparingTo("1.2");
    }

    @Test
    @Order(8)
    void softDeleteHidesProduct() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        Resp del = call(delete("/api/products/" + productId), admin, null);
        assertThat(del.http()).isEqualTo(200);

        Resp list = call(get("/api/products").param("keyword", "TST-X01"), admin, null);
        assertThat(list.body().path("total").asLong()).isZero();
    }

    @Test
    @Order(9)
    void alertReadHandleAndNegativeCase() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/alert-records").param("page", "1").param("size", "10"), filler, null);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(3);

        Resp readAll = call(post("/api/alert-records/read-all"), filler, null);
        assertThat(readAll.http()).isEqualTo(200);
        assertThat(readAll.body().path("affected").asInt()).isGreaterThanOrEqualTo(1);

        Resp unread = call(get("/api/alert-records").param("isRead", "0"), filler, null);
        assertThat(unread.body().path("total").asLong()).isZero();

        Resp open = call(get("/api/alert-records").param("status", "open"), filler, null);
        long openId = open.body().path("rows").get(0).path("id").asLong();
        Resp handled = call(post("/api/alert-records/" + openId + "/handle"), filler,
                Map.of("remark", "已现场处置"));
        assertThat(handled.http()).isEqualTo(200);
        assertThat(handled.body().path("status").asText()).isEqualTo("handled");
        assertThat(handled.body().path("handledBy").asText()).isEqualTo("13800000002");  // 账号标识已切为手机号，审计 actor 记录手机号

        Resp again = call(post("/api/alert-records/" + openId + "/handle"), filler, Map.of("remark", "重复"));
        assertThat(again.http()).as("已处理记录不能重复处理").isEqualTo(400);
    }

    @Test
    @Order(10)
    void energyMgmtUniqueDateAndRetrofitList() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/energy-mgmt"), filler, null);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(2);

        Resp dup = call(post("/api/energy-mgmt"), filler, Map.of(
                "atDate", "2026-01-15", "orgName", "设备动力部", "duty", "李国强"));
        assertThat(dup.http()).as("同企业同日只能一条能源管理记录").isEqualTo(409);

        Resp retro = call(get("/api/retrofits").param("year", "2026"), filler, null);
        assertThat(retro.body().path("total").asLong()).isEqualTo(2);
    }
}
