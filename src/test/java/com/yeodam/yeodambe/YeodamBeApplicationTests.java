package com.yeodam.yeodambe;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class YeodamBeApplicationTests {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void redisConnectionWorks() {
        String key = "test:connection";

        redisTemplate.opsForValue().set(key, "ok");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");
    }

    @Test
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void csrfTokenCanBeIssuedWithoutAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        "no-store"
                ))
                .andExpect(jsonPath("$.message")
                        .value("CSRF_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.data.headerName")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token")
                        .isNotEmpty())
                .andReturn();

        Cookie contextCookie = result.getResponse().getCookie("CSRF_CONTEXT");
        assertThat(contextCookie).isNotNull();
        assertThat(contextCookie.getPath()).isEqualTo("/");
        assertThat(contextCookie.isHttpOnly()).isTrue();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("SameSite=Lax")
                .contains("Max-Age=604800");
    }

}
