package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentConversionUnavailableException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Admission;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Operations;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.AttachmentConversionAdmissionService;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.jwt.ActiveLoginSessionValidator;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AttachmentConversionAdmissionHttpTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private TripRepository trips;
    @Autowired private EntityManager entityManager;
    @Autowired private AccessTokenIssuer tokens;
    @MockitoSpyBean private JdbcTemplate jdbc;
    @MockitoSpyBean private AttachmentConversionAdmissionService admission;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private CsrfTokenStore csrf;
    @MockitoBean private ActiveLoginSessionValidator sessions;

    private Long userId;
    private Long tripId;
    private String additionId;

    @BeforeEach
    void prepareOwner() {
        ReflectionTestUtils.setField(admission, "snapshot", null);
        ReflectionTestUtils.setField(admission, "closed", true);
        setMode(AttachmentConversionRoute.DB);
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "admission"));
        userId = user.getUserId();
        stats.saveAndFlush(new UserStats(user));
        tripId = trips.saveAndFlush(new Trip(userId, "admission", LocalDate.now(), LocalDate.now())).getId();
        additionId = UUID.randomUUID().toString();
        when(csrf.find("admission-browser")).thenReturn("csrf-token");
        when(sessions.validate(any())).thenReturn(OAuth2TokenValidatorResult.success());
        when(storage.createUploadUrl(anyString(), anyString(), any())).thenReturn("https://upload.test/photo");
        clearInvocations(jdbc, admission);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 스냅샷이_오래되거나_없으면_새_실행만_거부한다(boolean initial) throws Exception {
        prepareTrip(initial);
        setMode(AttachmentConversionRoute.KAFKA);
        mvc.perform(request(initial, 1, 1, true)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_CONVERSION_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(header().doesNotExist("Retry-After"));
        assertNoAdmissionQueries();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 진입이_거부되면_바이트를_예약하거나_URL을_발급하지_않는다(boolean initial) throws Exception {
        prepareTrip(initial);
        setMode(AttachmentConversionRoute.HOLD);
        mvc.perform(request(initial, 1, 1, true)).andExpect(status().isServiceUnavailable());
        entityManager.flush();
        assertThat(count(initial ? "trip_creation_executions" : "additional_attachment_executions")).isZero();
        assertThat(count(table(initial))).isZero();
        assertThat(reservedBytes()).isZero();
        verify(storage, never()).createUploadUrl(anyString(), anyString(), any());
        assertNoAdmissionQueries();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 기존_실행은_재발급하고_계속_진행할_수_있다(boolean initial) throws Exception {
        prepareTrip(initial);
        MvcResult first = mvc.perform(request(initial, 1, 2, false)).andExpect(status().isOk()).andReturn();
        String uploadId = json.readTree(first.getResponse().getContentAsString()).path("data").path("uploadId").asString();
        setMode(AttachmentConversionRoute.HOLD);
        clearInvocations(admission, jdbc);
        mvc.perform(request(initial, 1, 2, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadId").value(uploadId));
        assertThat(reservedBytes()).isEqualTo(1024);
        jdbc.update("UPDATE " + table(initial) + " SET status = ? WHERE trip_id = ?",
                initial ? "ACCEPTED" : "VERIFIED", tripId);
        entityManager.clear();
        mvc.perform(request(initial, 2, 2, true)).andExpect(status().isOk());
        entityManager.flush();
        assertThat(count(table(initial))).isEqualTo(2);
        assertThat(reservedBytes()).isEqualTo(2048);
        verify(admission, never()).assertNewExecutionAllowed();
        assertNoAdmissionQueries();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 기존_DB_모드의_호환성을_유지한다(boolean initial) throws Exception {
        prepareTrip(initial);
        mvc.perform(request(initial, 1, 1, true)).andExpect(status().isOk());
        entityManager.flush();
        assertThat(count(table(initial))).isEqualTo(1);
        assertThat(reservedBytes()).isEqualTo(1024);
        verify(storage).createUploadUrl(anyString(), anyString(), any());
        assertNoAdmissionQueries();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 입력_검증과_소유권과_보안_검사는_진입_허용_검사보다_먼저_실행한다(boolean initial) throws Exception {
        prepareTrip(initial);
        setMode(AttachmentConversionRoute.HOLD);
        mvc.perform(request(initial, 1, 1, true).content(body(initial, 1, 1, true).replace("1024", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(request(initial, 1, 1, true, Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        mvc.perform(postPath(initial).cookie(new Cookie("CSRF_CONTEXT", "admission-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token").contentType(MediaType.APPLICATION_JSON)
                        .content(body(initial, 1, 1, true))).andExpect(status().isUnauthorized());
        mvc.perform(postPath(initial).cookie(new Cookie("accessToken", tokens.issue(userId, "admission-session")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(initial, 1, 1, true)))
                .andExpect(status().isForbidden());
        verify(admission, never()).assertNewExecutionAllowed();
        verify(storage, never()).createUploadUrl(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void MySQL_표본_검사는_아직_이관되지_않은_변환_배치를_거부한다(boolean initial) throws Exception {
        prepareTrip(initial);
        mvc.perform(request(initial, 1, 2, false)).andExpect(status().isOk());
        entityManager.flush();
        jdbc.update("UPDATE " + table(initial) + " SET status = ? WHERE trip_id = ?",
                initial ? "ACCEPTED" : "VERIFIED", tripId);
        setMode(AttachmentConversionRoute.KAFKA);
        admission.sampleBacklog();
        assertThatThrownBy(admission::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
        jdbc.update("UPDATE " + table(initial) + " SET conversion_route = 'KAFKA' WHERE trip_id = ?", tripId);
        admission.sampleBacklog();
        assertThatCode(admission::assertNewExecutionAllowed).doesNotThrowAnyException();
    }

    @Test
    void MySQL_표본_검사는_전달_대신_작업을_세고_DB_기준_경과_시간을_사용한다() throws Exception {
        prepareTrip(true);
        mvc.perform(request(true, 1, 2, false)).andExpect(status().isOk());
        entityManager.flush();
        Long batchId = jdbc.queryForObject("SELECT upload_batch_id FROM initial_attachment_upload_batches WHERE trip_id = ?",
                Long.class, tripId);
        String taskId = UUID.randomUUID().toString();
        jdbc.update("UPDATE initial_attachment_upload_batches SET conversion_route = 'KAFKA' WHERE trip_id = ?", tripId);
        jdbc.update("""
                INSERT INTO attachment_conversion_tasks
                    (task_id, initial_batch_id, trip_id, kind, lane, total_photos, remaining_photos, total_bytes, remaining_bytes)
                VALUES (?, ?, ?, 'INITIAL', 'LIGHT', 3, 3, 300, 300)
                """, taskId, batchId, tripId);
        for (int delivery = 1; delivery <= 4; delivery++) {
            jdbc.update("""
                    INSERT INTO attachment_conversion_outbox
                        (outbox_id, task_id, delivery_no, trip_id, topic, payload, request_id)
                    VALUES (?, ?, ?, ?, 'yeodam-image-initial-light', '{}', 'request')
                    """, UUID.randomUUID().toString(), taskId, delivery, tripId);
        }
        setMode(AttachmentConversionRoute.KAFKA);
        admission.sampleBacklog();
        assertThatCode(admission::assertNewExecutionAllowed).doesNotThrowAnyException();
        jdbc.update("UPDATE attachment_conversion_tasks SET remaining_photos = 10 WHERE task_id = ?", taskId);
        admission.sampleBacklog();
        assertThatThrownBy(admission::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
        jdbc.update("""
                UPDATE attachment_conversion_tasks SET remaining_photos = 3,
                    created_at = CURRENT_TIMESTAMP(6) - INTERVAL 101 SECOND WHERE task_id = ?
                """, taskId);
        admission.sampleBacklog();
        assertThatThrownBy(admission::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED' WHERE task_id = ?", taskId);
        admission.sampleBacklog();
        assertThatCode(admission::assertNewExecutionAllowed).doesNotThrowAnyException();
    }

    private void setMode(AttachmentConversionRoute mode) {
        ReflectionTestUtils.setField(admission, "properties", new AttachmentConversionProperties(
                mode,
                new Admission(Map.of(Lane.LIGHT, 10L, Lane.HEIC, 10L),
                        Map.of(Lane.LIGHT, 100000L, Lane.HEIC, 100000L),
                        Duration.ofSeconds(100), Duration.ofSeconds(10), Duration.ofSeconds(1), 0.5),
                new Operations(false),
                null,
                new AttachmentConversionProperties.Recovery(Duration.ofMinutes(5), 10, 2, 10),
                true,
                true,
                true
        ));
    }

    private void prepareTrip(boolean initial) {
        if (!initial) {
            jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
            entityManager.clear();
        }
    }

    private MockHttpServletRequestBuilder request(boolean initial, int batch, int total, boolean complete) {
        return request(initial, batch, total, complete, userId);
    }

    private MockHttpServletRequestBuilder request(boolean initial, int batch, int total, boolean complete, Long actor) {
        return postPath(initial).cookie(new Cookie("accessToken", tokens.issue(actor, "admission-session")),
                        new Cookie("CSRF_CONTEXT", "admission-browser")).header("X-CSRF-TOKEN", "csrf-token")
                .contentType(MediaType.APPLICATION_JSON).content(body(initial, batch, total, complete));
    }

    private MockHttpServletRequestBuilder postPath(boolean initial) {
        return post("/api" + path(initial)).contextPath("/api").servletPath(path(initial));
    }

    private String path(boolean initial) {
        return "/trips/" + tripId + (initial ? "/initial-attachments/upload-urls" : "/attachments/upload-urls");
    }

    private String body(boolean initial, int batch, int total, boolean complete) {
        return "{" + (initial ? "" : "\"additionId\":\"" + additionId + "\",")
                + "\"batchNo\":" + batch + ",\"totalAttachmentCount\":" + total + ",\"complete\":" + complete
                + ",\"attachments\":[{\"fileName\":\"photo.jpg\",\"contentType\":\"image/jpeg\",\"sizeBytes\":1024}]}";
    }

    private String table(boolean initial) {
        return initial ? "initial_attachment_upload_batches" : "additional_attachment_upload_batches";
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE trip_id = ?", Long.class, tripId);
    }

    private long reservedBytes() {
        return jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, userId);
    }

    private void assertNoAdmissionQueries() {
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(invocation -> {
            Object[] arguments = invocation.getArguments();
            return arguments.length > 0 && arguments[0] instanceof String sql
                    && (sql.contains("SUM(") || sql.contains("conversion_route IN"));
        });
    }
}
