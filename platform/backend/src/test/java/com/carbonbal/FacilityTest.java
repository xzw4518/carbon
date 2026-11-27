package com.carbonbal;

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

/** 设施树：层级推导、父节点校验、编码唯一、删除保护。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FacilityTest extends ApiTestSupport {

    private static Long childId;

    @Test
    @Order(1)
    void listSeededTreeAndCreateChild() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp list = call(get("/api/facilities").param("size", "50"), filler, null);
        assertThat(list.http()).isEqualTo(200);
        assertThat(list.body().path("total").asLong()).isGreaterThanOrEqualTo(5);

        Resp created = call(post("/api/facilities"), filler, Map.of(
                "name", "测试车间", "code", "IT-F1-9", "parentId", 1, "sort", 9, "remark", "接口测试"));
        assertThat(created.http()).isEqualTo(200);
        childId = created.body().path("id").asLong();
        assertThat(created.body().path("level").asInt()).as("父级为 1 级，子级应为 2").isEqualTo(2);
        assertThat(created.body().path("parentName").asText()).isEqualTo("一号厂区");

        Resp dup = call(post("/api/facilities"), filler, Map.of(
                "name", "重复编码", "code", "IT-F1-9", "parentId", 1));
        assertThat(dup.http()).isEqualTo(409);
    }

    @Test
    @Order(2)
    void parentMustNotBeSelfOrDescendant() throws Exception {
        String filler = login("filler", "Test@2026!Pass");
        Resp cycle = call(put("/api/facilities/1"), filler, Map.of(
                "name", "一号厂区", "code", "F1", "parentId", childId, "version", 0));
        assertThat(cycle.http()).isEqualTo(400);
        assertThat(cycle.body().path("message").asText()).contains("自身或下级");

        Resp missing = call(post("/api/facilities"), filler, Map.of(
                "name", "孤儿设施", "code", "IT-ORPHAN", "parentId", 99999));
        assertThat(missing.http()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void deleteProtectsParentAndAllowsLeaf() throws Exception {
        String admin = login("admin", "Admin@2026!Pass");
        String viewer = login("viewer", "Test@2026!Pass");

        Resp denied = call(delete("/api/facilities/" + childId), viewer, null);
        assertThat(denied.http()).isEqualTo(403);

        Resp parent = call(delete("/api/facilities/1"), admin, null);
        assertThat(parent.http()).as("有下级时不能删除").isEqualTo(409);

        Resp leaf = call(delete("/api/facilities/" + childId), admin, null);
        assertThat(leaf.http()).isEqualTo(200);
        assertThat(call(get("/api/facilities/" + childId), admin, null).http()).isEqualTo(404);
    }
}
