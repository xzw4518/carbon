package com.carbonbal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 供应链树：层级推导、父级校验（自环/成环/跨租户）、删除保护、树接口。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SupplierTreeTest extends ApiTestSupport {

    private Map<String, Object> body(String name, String product, Long parentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("product", product);
        m.put("unit", "吨");
        m.put("fpUnit", "tCO2e/t");
        m.put("footprint", 1.2);
        m.put("date", "2026-11-01");
        m.put("sort", 1);
        if (parentId != null) m.put("parentId", parentId);
        return m;
    }

    @Test
    void treeLifecycleWithParentValidation() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        String sfx = String.valueOf(System.currentTimeMillis() % 1000000);

        Resp rootA = call(post("/api/suppliers"), filler, body("根A-" + sfx, "产品A", null));
        assertThat(rootA.http()).isEqualTo(200);
        long a = rootA.body().path("id").asLong();
        assertThat(rootA.body().path("level").asText()).as("无上级应为一级").isEqualTo("一级");

        Resp rootB = call(post("/api/suppliers"), filler, body("根B-" + sfx, "产品B", null));
        long b = rootB.body().path("id").asLong();

        Resp child = call(post("/api/suppliers"), filler, body("子C-" + sfx, "产品C", a));
        assertThat(child.http()).isEqualTo(200);
        long c = child.body().path("id").asLong();
        assertThat(child.body().path("parentId").asLong()).isEqualTo(a);
        assertThat(child.body().path("level").asText()).as("父为一级，子应为二级").isEqualTo("二级");

        Resp grand = call(post("/api/suppliers"), filler, body("孙D-" + sfx, "产品D", c));
        long d = grand.body().path("id").asLong();
        assertThat(grand.body().path("level").asText()).isEqualTo("三级");

        // 自环
        Resp self = call(put("/api/suppliers/" + a), filler, body("根A-" + sfx, "产品A", a));
        assertThat(self.http()).as("上级不能是自己").isEqualTo(400);
        assertThat(self.code()).isEqualTo("VALIDATION");

        // 成环：把根挂到自己的孙子下
        Resp cycle = call(put("/api/suppliers/" + a), filler, body("根A-" + sfx, "产品A", d));
        assertThat(cycle.http()).as("上级不能是后代").isEqualTo(400);

        // 删除保护（删除需管理员）
        String admin = login("admin", "Admin@2026!Pass");
        Resp delWithChild = call(delete("/api/suppliers/" + a), admin, null);
        assertThat(delWithChild.http()).isEqualTo(409);
        assertThat(delWithChild.code()).isEqualTo("REFERENCED");

        // 换挂：C 从 A 移到 B 下，C 仍二级，D 仍三级
        Resp moved = call(put("/api/suppliers/" + c), filler, body("子C-" + sfx, "产品C", b));
        assertThat(moved.http()).isEqualTo(200);
        assertThat(moved.body().path("parentId").asLong()).isEqualTo(b);
        Resp detailD = call(get("/api/suppliers/" + d), filler, null);
        assertThat(detailD.body().path("level").asText()).as("换挂后后代层级应重算").isEqualTo("三级");

        // 树接口：B 下应能看到 C，C 下应能看到 D
        Resp tree = call(get("/api/suppliers/tree"), filler, null);
        assertThat(tree.http()).isEqualTo(200);
        JsonNode bNode = null;
        for (JsonNode n : tree.body()) if (n.path("id").asLong() == b) bNode = n;
        assertThat(bNode).as("树接口应返回根节点 B").isNotNull();
        JsonNode cNode = null;
        for (JsonNode n : bNode.path("children")) if (n.path("id").asLong() == c) cNode = n;
        assertThat(cNode).as("B 的 children 应包含 C").isNotNull();
        assertThat(cNode.path("children").get(0).path("id").asLong()).isEqualTo(d);

        // 清理：先叶后根
        assertThat(call(delete("/api/suppliers/" + d), admin, null).http()).isEqualTo(200);
        assertThat(call(delete("/api/suppliers/" + c), admin, null).http()).isEqualTo(200);
        assertThat(call(delete("/api/suppliers/" + a), admin, null).http()).isEqualTo(200);
        assertThat(call(delete("/api/suppliers/" + b), admin, null).http()).isEqualTo(200);
    }
}
