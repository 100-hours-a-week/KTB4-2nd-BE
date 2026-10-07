package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.security.TokenHasher;
import jakarta.servlet.http.Cookie;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CsrfStoreFailureIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TokenHasher hasher;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void csrfFilterDoesNotInterceptBusinessDatabaseFailure() {
        var failure = new DataAccessResourceFailureException("Business DB failure");
        var filter = new CsrfStoreFailureFilter(objectMapper);
        Assertions.assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) -> { throw failure; }))
                .isSameAs(failure);
    }

    @Test
    void corruptedRedisTypeReturns503DuringCsrfFilterAndIssuance() throws Exception {
        String context = UUID.randomUUID().toString();
        String key = "yeodam:test:auth:csrf:" + hasher.hash(context);
        redis.opsForHash().put(key, "corrupt", "private-value");
        try {
            mvc.perform(post("/auth/token/exchange")
                            .cookie(new Cookie("CSRF_CONTEXT", context))
                            .header("X-CSRF-TOKEN", "private-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
            mvc.perform(get("/auth/csrf").cookie(new Cookie("CSRF_CONTEXT", context)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
        } finally {
            redis.delete(key);
        }
    }
}
