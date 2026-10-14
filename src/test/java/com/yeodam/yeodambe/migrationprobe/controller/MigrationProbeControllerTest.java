package com.yeodam.yeodambe.migrationprobe.controller;

import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.common.logging.RequestIdFilter;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeHashConflictException;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeStoreUnavailableException;
import com.yeodam.yeodambe.migrationprobe.filter.MigrationProbeBodyLimitFilter;
import com.yeodam.yeodambe.migrationprobe.service.MigrationProbeService;
import com.yeodam.yeodambe.migrationprobe.service.response.MigrationProbeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MigrationProbeControllerTest {

    private static final String RUN = "00000000-0000-4000-8000-000000000001";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000002";
    private static final String HASH = "a".repeat(64);
    private static final String BODY = """
            {"runId":"%s","requestId":"%s","payloadHash":"%s"}
            """.formatted(RUN, REQUEST, HASH);

    private final MigrationProbeService service = mock(MigrationProbeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MigrationProbeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter(), new MigrationProbeBodyLimitFilter(new ObjectMapper()))
                .build();
    }

    @Test
    void newCommitReturnsCreatedEnvelope() throws Exception {
        when(service.write(RUN, REQUEST, HASH)).thenReturn(new MigrationProbeResponse(RUN, REQUEST, "created"));

        mvc.perform(request(BODY).header("X-Request-ID", REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_CREATED"))
                .andExpect(jsonPath("$.data.runId").value(RUN))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST))
                .andExpect(jsonPath("$.data.result").value("created"));
    }

    @Test
    void sameHashReturnsAlreadyExistsEnvelope() throws Exception {
        when(service.write(RUN, REQUEST, HASH)).thenReturn(new MigrationProbeResponse(RUN, REQUEST, "already_exists"));

        mvc.perform(request(BODY).header("X-Request-ID", REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.data.result").value("already_exists"));
    }

    @Test
    void differentHashReturnsConflict() throws Exception {
        when(service.write(anyString(), anyString(), anyString())).thenThrow(new MigrationProbeHashConflictException());

        mvc.perform(request(BODY).header("X-Request-ID", REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_HASH_CONFLICT"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void storeFailureHasProbeSpecificErrorWithoutSecrets() throws Exception {
        when(service.write(anyString(), anyString(), anyString()))
                .thenThrow(new MigrationProbeStoreUnavailableException("commit", new IllegalStateException("SECRET")));

        mvc.perform(request(BODY).header("X-Request-ID", REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_STORE_UNAVAILABLE"));
    }

    @Test
    void generatedRequestIdCannotReplaceMissingHeader() throws Exception {
        mvc.perform(request(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void mismatchedOrDuplicateHeaderIsRejected() throws Exception {
        mvc.perform(request(BODY).header("X-Request-ID", RUN)).andExpect(status().isBadRequest());
        mvc.perform(request(BODY).header("X-Request-ID", REQUEST, REQUEST)).andExpect(status().isBadRequest());
        mvc.perform(request(BODY).header("X-Request-ID", "invalid header")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidJsonNeverWrites(String body) throws Exception {
        mvc.perform(request(body).header("X-Request-ID", REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void exactly1024BytesAreAcceptedAnd1025AreRejected() throws Exception {
        when(service.write(RUN, REQUEST, HASH)).thenReturn(new MigrationProbeResponse(RUN, REQUEST, "created"));
        String padded = BODY + " ".repeat(1024 - BODY.length());
        mvc.perform(request(padded).header("X-Request-ID", REQUEST)).andExpect(status().isCreated());
        mvc.perform(request(padded + " ").header("X-Request-ID", REQUEST))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value("MIGRATION_WRITE_PROBE_BODY_TOO_LARGE"));
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{}", "null", "[]", "\"text\"", "{", BODY + "{}",
                BODY.replace("\"runId\":\"" + RUN + "\",", ""),
                BODY.replace("\"" + RUN + "\"", "null"),
                BODY.replace(RUN, " "), BODY.replace(RUN, "1-1-1-1-1"),
                BODY.replace(RUN, "ABCDEF00-0000-4000-8000-000000000001"),
                BODY.replace(HASH, "A".repeat(64)), BODY.replace(HASH, "g".repeat(64)),
                BODY.replace(HASH, "a".repeat(63)),
                BODY.replace("\"" + HASH + "\"", "1".repeat(64)),
                BODY.replace("\"" + HASH + "\"", "true"),
                BODY.replace("\"" + HASH + "\"", "[]"),
                BODY.replace("}", ",\"extra\":\"x\"}"),
                BODY.replace("}", ",\"runId\":\"" + RUN + "\"}")
        );
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/internal/migration-write-probe")
                .contextPath("/api")
                .servletPath("/internal/migration-write-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
