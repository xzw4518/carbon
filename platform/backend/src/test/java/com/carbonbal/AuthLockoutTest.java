package com.carbonbal;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 登录失败锁定：连续 5 次错误后第 6 次返回 429，正常账号不受影响。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthLockoutTest extends ApiTestSupport {

    @Test
    @Order(1)
    void fifthFailureLocksAndSixthIs429() throws Exception {
        for (int i = 1; i <= 5; i++) {
            Resp r = attemptLogin("nobody", "wrong-" + i, true);
            assertThat(r.http()).as("第 " + i + " 次失败应为 401").isEqualTo(401);
        }
        Resp locked = attemptLogin("nobody", "wrong-6", true);
        assertThat(locked.http()).as("锁定期内第 6 次应 429").isEqualTo(429);
        assertThat(locked.body().path("message").asText()).contains("登录失败次数过多");
        assertThat(locked.code()).as("锁定错误码与文档一致").isEqualTo("AUTH_RATE_LIMITED");
    }

    @Test
    @Order(2)
    void otherAccountsStillLogin() throws Exception {
        Resp filler = attemptLogin("filler", "Test@2026!Pass", true);
        assertThat(filler.http()).isEqualTo(200);
        assertThat(filler.body().path("token").asText()).isNotBlank();

        Resp viewer = attemptLogin("viewer", "Test@2026!Pass", true);
        assertThat(viewer.http()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void successfulLoginResetsCounter() throws Exception {
        for (int i = 1; i <= 4; i++) {
            attemptLogin("admin", "bad-" + i, true);
        }
        Resp ok = attemptLogin("admin", "Admin@2026!Pass", true);
        assertThat(ok.http()).as("第 5 次正确登录应成功并清零").isEqualTo(200);

        Resp wrong = attemptLogin("admin", "bad-again", true);
        assertThat(wrong.http()).as("清零后再错一次仍是 401").isEqualTo(401);
    }
}
