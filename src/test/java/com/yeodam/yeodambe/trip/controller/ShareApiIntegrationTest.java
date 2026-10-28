package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.ShareTripService;
import com.yeodam.yeodambe.trip.service.request.TripShareCreateRequest;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShareApiIntegrationTest.TimeConfig.class})
class ShareApiIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private TripRepository trips;
    @Autowired
    private ShareTripService shares;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private Long ownerId;
    private Long targetId;
    private Long tripId;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean
        @Primary
        Clock shareTestClock() {
            return Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void prepare() {
        ownerId = users.save(new User(UUID.randomUUID() + "@test.com", "소유자")).getUserId();
        String targetEmail = "Friend+" + UUID.randomUUID() + "@test.com";
        targetId = users.save(new User(targetEmail, "여행친구")).getUserId();
        tripId = trips.save(new Trip(ownerId, "공유여행", LocalDate.now(), LocalDate.now())).getId();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
    }

    @Test
    void 미인증_공유_등록을_거절한다() throws Exception {
        mvc.perform(post("/trips/{id}/shares", tripId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 등록은_24시간_권한을_저장하고_중복은_기존값을_유지한다() throws Exception {
        mvc.perform(register(tripId, targetId, "READ"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_CREATE_SUCCESS"))
                .andExpect(jsonPath("$.data.granteeUserId").value(targetId))
                .andExpect(jsonPath("$.data.permission").value("READ"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-29T15:00:00+09:00"));
        mvc.perform(register(tripId, targetId, "WRITE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_ALREADY_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT permission FROM trip_share_users WHERE trip_id = ?",
                String.class, tripId)).isEqualTo("READ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "\"0\"", "\"read\"", "[]", "\"OWNER\"", "\"\""})
    void enum_우회_입력을_거절한다(String permission) throws Exception {
        mvc.perform(postRequest(tripId.toString()).content(
                        "{\"granteeUserId\":" + targetId + ",\"permission\":" + permission + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_SHARE_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "{\"granteeUserId\":-1,\"permission\":\"READ\"}"})
    void 잘못된_본문을_거절한다(String body) throws Exception {
        mvc.perform(postRequest(tripId.toString()).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_SHARE_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void 잘못된_여행_ID를_거절한다(String id) throws Exception {
        mvc.perform(postRequest(id).content(body(targetId, "READ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_SHARE_REQUEST"));
    }

    @Test
    void 권한과_상태와_등록대상을_검증한다() throws Exception {
        mvc.perform(register(tripId, ownerId, "READ")).andExpect(status().isBadRequest());
        mvc.perform(register(tripId, Long.MAX_VALUE, "READ")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET deleted_at = NOW(6) WHERE user_id = ?", targetId);
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isBadRequest());
        mvc.perform(register(tripId, ownerId, "READ").with(jwt().jwt(token -> token.subject(targetId.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_CREATE_FORBIDDEN"));
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_NOT_AVAILABLE"));
        jdbc.update("UPDATE trips SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_share_users WHERE trip_id = ?",
                Long.class, tripId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 만료경계와_철회관계는_ID와_생성시각을_보존해_재등록한다(boolean revoked) throws Exception {
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT trip_share_id FROM trip_share_users WHERE trip_id = ?", Long.class, tripId);
        LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM trip_share_users WHERE trip_id = ?", LocalDateTime.class, tripId);
        if (revoked) {
            jdbc.update("UPDATE trip_share_users SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
        } else {
            jdbc.update("UPDATE trip_share_users SET expires_at = '2026-10-28 15:00:00' WHERE trip_id = ?", tripId);
        }
        mvc.perform(register(tripId, targetId, "WRITE")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tripShareId").value(id))
                .andExpect(jsonPath("$.data.permission").value("WRITE"));
        assertThat(jdbc.queryForObject("SELECT created_at FROM trip_share_users WHERE trip_id = ?", LocalDateTime.class, tripId)).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM trip_share_users WHERE trip_id = ?", LocalDateTime.class, tripId)).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동시_최초등록과_재등록은_한건만_성공한다(boolean reactivation) throws Exception {
        if (reactivation) {
            mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isCreated());
            jdbc.update("UPDATE trip_share_users SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 대기 시간 초과");
            }
            return mvc.perform(register(tripId, targetId, "WRITE")).andReturn().getResponse().getStatus();
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(request);
            Future<Integer> second = executor.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_share_users WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(1L);
    }

    @Test
    void DB도_중복과_잘못된_권한과_없는_수신자를_거절한다() throws Exception {
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO trip_share_users (trip_id, user_id, grantee_user_id, expires_at)
                VALUES (?, ?, ?, '2026-10-29 15:00:00')
                """, tripId, ownerId, targetId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trip_share_users SET permission = 'OWNER' WHERE trip_id = ?", tripId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_trip_share_permission");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trip_share_users SET grantee_user_id = ? WHERE trip_id = ?", Long.MAX_VALUE, tripId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 재활성화가_롤백되면_이전_권한과_철회상태를_유지한다() throws Exception {
        mvc.perform(register(tripId, targetId, "READ")).andExpect(status().isCreated());
        jdbc.update("UPDATE trip_share_users SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            shares.register(tripId, ownerId, new TripShareCreateRequest(targetId, TripSharePermission.WRITE));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT permission FROM trip_share_users WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("READ");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM trip_share_users WHERE trip_id = ?", LocalDateTime.class, tripId))
                .isNotNull();
    }

    @Test
    void 삭제가_먼저_여행을_잠그면_대기중인_등록은_404로_끝난다() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> result = transaction.execute(status -> {
                trips.findActiveForUpdate(tripId).orElseThrow();
                Future<Integer> registration = executor.submit(() -> {
                    started.countDown();
                    return mvc.perform(register(tripId, targetId, "READ")).andReturn().getResponse().getStatus();
                });
                await(started);
                jdbc.update("UPDATE trips SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
                return registration;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_share_users WHERE trip_id = ?", Long.class, tripId))
                .isZero();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private MockHttpServletRequestBuilder postRequest(String id) {
        return post("/trips/{id}/shares", id).contentType(MediaType.APPLICATION_JSON)
                .with(jwt().jwt(token -> token.subject(ownerId.toString()))).with(csrf());
    }

    private MockHttpServletRequestBuilder register(Long id, Long grantee, String permission) {
        return postRequest(id.toString()).content(body(grantee, permission));
    }

    private String body(Long grantee, String permission) {
        return "{\"granteeUserId\":" + grantee + ",\"permission\":\"" + permission + "\"}";
    }
}
