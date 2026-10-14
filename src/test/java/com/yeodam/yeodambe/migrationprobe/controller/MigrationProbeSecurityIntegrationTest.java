package com.yeodam.yeodambe.migrationprobe.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.yeodam.yeodambe.user.security.TokenHasher;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "staging"})
@Import(TestcontainersConfiguration.class)
class MigrationProbeSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRegistrationService users;
    @Autowired
    private LoginSessionIssuer sessions;
    @Autowired
    private AccessTokenIssuer tokens;
    @Autowired
    private CsrfTokenStore csrf;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TokenHasher hasher;

    private String sid;
    private Long userId;
    private String token;
    private String context;
    private String csrfToken;
    private String runId;
    private String requestId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID().toString();
        requestId = UUID.randomUUID().toString();
        var user = users.register("probe-" + runId + "@yeodam.test", "시험회원", OAuthProvider.KAKAO, "probe-" + runId);
        var session = sessions.issue(user.getUserId());
        userId = user.getUserId();
        sid = session.sid();
        token = tokens.issue(userId, sid);
        context = "probe-context-" + runId;
        csrfToken = "probe-csrf-" + runId;
        csrf.save(context, csrfToken);
        jdbc.execute("DROP TABLE IF EXISTS migration_write_probe");
        jdbc.execute("""
                CREATE TABLE migration_write_probe (
                    run_id CHAR(36) NOT NULL,
                    request_id CHAR(36) NOT NULL,
                    payload_hash CHAR(64) NOT NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (run_id, request_id)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void realRedisSessionAndCsrfSupportCreateRetryAndConflict() throws Exception {
        mvc.perform(authenticated(body("a"))).andExpect(status().isCreated());
        mvc.perform(authenticated(body("a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("already_exists"));
        mvc.perform(authenticated(body("b"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_write_probe", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM migration_write_probe", String.class))
                .isEqualTo("a".repeat(64));
    }

    @Test
    void missingAccessCookieIsRejectedEvenWithValidCsrf() throws Exception {
        mvc.perform(request(body("a"))
                        .cookie(new Cookie("CSRF_CONTEXT", context))
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isUnauthorized());
        assertNoRows();
    }

    @Test
    void missingOrWrongCsrfIsRejectedBeforeBodyLimit() throws Exception {
        mvc.perform(request(body("a")).cookie(new Cookie("accessToken", token)))
                .andExpect(status().isForbidden());
        mvc.perform(request("a".repeat(2048))
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", context))
                        .header("X-CSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
        assertNoRows();
    }

    @Test
    void authenticatedLargeOrInvalidRequestDoesNotWrite() throws Exception {
        mvc.perform(authenticated(" ".repeat(1025))).andExpect(status().is(413));
        mvc.perform(authenticated(body("a")).header("X-Request-ID", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        assertNoRows();
    }

    @Test
    void missingTableFailsOnlyProbeRequest() throws Exception {
        jdbc.execute("DROP TABLE migration_write_probe");
        mvc.perform(authenticated(body("a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_STORE_UNAVAILABLE"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void mysqlV1SessionAloneDoesNotAuthenticateV2Probe() throws Exception {
        String mysqlSid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO login_sessions (sid, user_id, refresh_token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                """, mysqlSid, userId, "b".repeat(64), LocalDateTime.now().plusDays(1));
        token = tokens.issue(userId, mysqlSid);

        mvc.perform(authenticated(body("a"))).andExpect(status().isUnauthorized());
        assertNoRows();
    }

    @Test
    void deletedRedisSessionDoesNotAuthenticateProbe() throws Exception {
        redis.delete("yeodam:test:auth:session:" + sid);

        mvc.perform(authenticated(body("a"))).andExpect(status().isUnauthorized());
        assertNoRows();
    }

    @Test
    void deletedRedisCsrfDoesNotPermitProbe() throws Exception {
        csrf.delete(context);

        mvc.perform(authenticated(body("a"))).andExpect(status().isForbidden());
        assertNoRows();
    }

    @Test
    void corruptRedisCsrfCannotBecomeSuccessfulWrite() throws Exception {
        String key = "yeodam:test:auth:csrf:" + hasher.hash(context);
        redis.delete(key);
        redis.opsForHash().put(key, "corrupt", "private-value");
        try {
            mvc.perform(authenticated(body("a")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
            assertNoRows();
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void corruptRedisSessionCannotBecomeSuccessfulWrite() throws Exception {
        String key = "yeodam:test:auth:session:" + sid;
        redis.delete(key);
        redis.opsForValue().set(key, "private-corrupt-session");
        try {
            mvc.perform(authenticated(body("a")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
            assertNoRows();
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void probeDoesNotCreateBusinessExecutionsOrOutboxRows() throws Exception {
        var tables = java.util.List.of("trip_creation_executions", "story_generation_outbox", "trips", "files");
        var before = tables.stream()
                .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                .toList();

        mvc.perform(authenticated(body("a"))).andExpect(status().isCreated());

        var after = tables.stream()
                .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                .toList();
        assertThat(after).isEqualTo(before);
    }

    private MockHttpServletRequestBuilder authenticated(String body) {
        return request(body)
                .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", context))
                .header("X-CSRF-TOKEN", csrfToken);
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/internal/migration-write-probe")
                .contextPath("/api")
                .servletPath("/internal/migration-write-probe")
                .header("X-Request-ID", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String body(String hex) {
        return """
                {"runId":"%s","requestId":"%s","payloadHash":"%s"}
                """.formatted(runId, requestId, hex.repeat(64));
    }

    private void assertNoRows() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_write_probe", Integer.class)).isZero();
    }
}
