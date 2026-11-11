package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.ShareTripService;
import com.yeodam.yeodambe.trip.service.request.TripShareCreateRequest;
import com.yeodam.yeodambe.trip.service.request.cursor.TripShareCursor;
import com.yeodam.yeodambe.trip.service.response.TripShareListResponse;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
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
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
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
    void 공유_목록은_미인증이면_거절하고_인증된_GET은_CSRF_없이_허용한다() throws Exception {
        mvc.perform(get("/trips/{id}/shares", tripId)).andExpect(status().isUnauthorized());
        mvc.perform(listRequest(tripId.toString(), ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 11})
    void 공유_목록을_다섯_개씩_누락과_중복_없이_이어_조회한다(int count) throws Exception {
        List<Long> expectedIds = IntStream.range(0, count)
                .mapToObj((int index) -> registerTarget(index % 2 == 0
                        ? TripSharePermission.READ : TripSharePermission.WRITE))
                .toList();
        List<Long> actualIds = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = listRequest(tripId.toString(), ownerId);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(body).path("data");
            JsonNode items = data.path("items");
            int pageSize = Math.min(5, count - actualIds.size());
            assertThat(items.size()).isEqualTo(pageSize);
            for (JsonNode item : items) {
                actualIds.add(item.path("tripShareId").asLong());
                assertThat(item.path("email").asString()).endsWith("@test.com");
                assertThat(item.path("permission").asString()).isIn("READ", "WRITE");
                assertThat(item.path("expiresAt").asString()).isEqualTo("2026-10-29T15:00:00+09:00");
            }
            assertThat(data.path("hasNext").asBoolean()).isEqualTo(actualIds.size() < count);
            if (actualIds.size() < count) {
                cursor = data.path("nextCursor").asString();
                assertThat(TripShareCursor.decode(cursor, tripId, objectMapper).lastTripShareId())
                        .isEqualTo(actualIds.getLast());
            } else {
                assertThat(data.path("nextCursor").isNull()).isTrue();
                cursor = null;
            }
        } while (cursor != null);
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ", "WRITE"})
    void 공유받은_사용자도_다른_공유_대상자_목록을_조회할_수_없다(String permission) throws Exception {
        shares.register(tripId, ownerId, new TripShareCreateRequest(targetId, TripSharePermission.valueOf(permission)));

        mvc.perform(listRequest(tripId.toString(), targetId).param("cursor", "broken!"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 소유자와_미삭제_여행을_검증하고_완료_상태는_요구하지_않는다() throws Exception {
        mvc.perform(listRequest(tripId.toString(), targetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(listRequest(Long.toString(Long.MAX_VALUE), ownerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        mvc.perform(listRequest(tripId.toString(), ownerId)).andExpect(status().isOk());
        jdbc.update("UPDATE trips SET deleted_at = NOW(6) WHERE trip_id = ?", tripId);
        mvc.perform(listRequest(tripId.toString(), ownerId)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void 공유_목록의_잘못된_여행_ID는_일반_입력_오류다(String id) throws Exception {
        mvc.perform(listRequest(id, ownerId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "broken!", "e30"})
    void 공유_목록의_잘못된_커서는_커서_오류다(String cursor) throws Exception {
        mvc.perform(listRequest(tripId.toString(), ownerId).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_CURSOR"));
    }

    @Test
    void 다른_여행의_커서를_거절한다() throws Exception {
        String cursor = new TripShareCursor(tripId + 1, 1L).encode(objectMapper);
        mvc.perform(listRequest(tripId.toString(), ownerId).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_CURSOR"));
    }

    @Test
    void 만료경계_철회_탈퇴_발급자와_자기자신_조건을_조회에서_적용한다() throws Exception {
        List<Long> ids = IntStream.range(0, 7)
                .mapToObj((int index) -> registerTarget(TripSharePermission.READ)).toList();
        jdbc.update("UPDATE trip_share_users SET expires_at = '2026-10-28 14:59:59.999999' WHERE trip_share_id = ?", ids.get(0));
        jdbc.update("UPDATE trip_share_users SET expires_at = '2026-10-28 15:00:00' WHERE trip_share_id = ?", ids.get(1));
        jdbc.update("UPDATE trip_share_users SET deleted_at = NOW(6) WHERE trip_share_id = ?", ids.get(2));
        jdbc.update("UPDATE users SET deleted_at = NOW(6) WHERE user_id = (SELECT grantee_user_id FROM trip_share_users WHERE trip_share_id = ?)", ids.get(3));
        jdbc.update("UPDATE trip_share_users SET user_id = ? WHERE trip_share_id = ?", targetId, ids.get(4));
        jdbc.update("UPDATE trip_share_users SET grantee_user_id = ? WHERE trip_share_id = ?", ownerId, ids.get(5));
        jdbc.update("UPDATE trip_share_users SET expires_at = '2026-10-28 15:00:00.000001' WHERE trip_share_id = ?", ids.get(6));
        Trip otherTrip = trips.save(new Trip(ownerId, "다른여행", LocalDate.now(), LocalDate.now()));
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", otherTrip.getId());
        shares.register(otherTrip.getId(), ownerId, new TripShareCreateRequest(targetId, TripSharePermission.WRITE));

        mvc.perform(listRequest(tripId.toString(), ownerId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].tripShareId").value(ids.get(6)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 커서_행을_삭제해도_이어_조회하고_남은_관계가_만료되면_빈_마지막_페이지다() throws Exception {
        List<Long> ids = IntStream.range(0, 6)
                .mapToObj((int index) -> registerTarget(TripSharePermission.READ)).toList();
        TripShareListResponse first = shares.findShares(tripId, ownerId, null);
        jdbc.update("DELETE FROM trip_share_users WHERE trip_share_id = ?", ids.get(4));

        mvc.perform(listRequest(tripId.toString(), ownerId).param("cursor", first.nextCursor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tripShareId").value(ids.get(5)));
        jdbc.update("UPDATE trip_share_users SET expires_at = '2026-10-28 15:00:00' WHERE trip_share_id = ?", ids.get(5));
        mvc.perform(listRequest(tripId.toString(), ownerId).param("cursor", first.nextCursor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @Test
    void 조회는_공유를_변경하지_않고_대상자_수가_늘어도_SQL은_두_번이다() {
        Long shareId = registerTarget(TripSharePermission.WRITE);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM trip_share_users WHERE trip_share_id = ?", shareId);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        TripShareListResponse first = shares.findShares(tripId, ownerId, null);

        assertThat(first.items()).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM trip_share_users WHERE trip_share_id = ?", shareId)).isEqualTo(before);
        IntStream.range(0, 5).forEach((int index) -> registerTarget(TripSharePermission.READ));
        statistics.clear();

        TripShareListResponse page = shares.findShares(tripId, ownerId, null);

        assertThat(page.items()).hasSize(5);
        assertThat(page.hasNext()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void 재활성화한_공유를_첫_페이지에서_갱신된_권한으로_조회한다() throws Exception {
        Long shareId = shares.register(tripId, ownerId,
                new TripShareCreateRequest(targetId, TripSharePermission.READ)).tripShareId();
        jdbc.update("UPDATE trip_share_users SET deleted_at = NOW(6) WHERE trip_share_id = ?", shareId);
        shares.register(tripId, ownerId, new TripShareCreateRequest(targetId, TripSharePermission.WRITE));
        String email = users.findById(targetId).orElseThrow().getEmail();

        mvc.perform(listRequest(tripId.toString(), ownerId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tripShareId").value(shareId))
                .andExpect(jsonPath("$.data.items[0].granteeUserId").value(targetId))
                .andExpect(jsonPath("$.data.items[0].email").value(email))
                .andExpect(jsonPath("$.data.items[0].permission").value("WRITE"))
                .andExpect(jsonPath("$.data.items[0].expiresAt").value("2026-10-29T15:00:00+09:00"));
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

    private MockHttpServletRequestBuilder listRequest(String id, Long requesterId) {
        return get("/trips/{id}/shares", id)
                .with(jwt().jwt((Jwt.Builder token) -> token.subject(requesterId.toString())));
    }

    private Long registerTarget(TripSharePermission permission) {
        Long userId = users.save(new User(UUID.randomUUID() + "@test.com", "공유대상")).getUserId();
        return shares.register(tripId, ownerId, new TripShareCreateRequest(userId, permission)).tripShareId();
    }

    private MockHttpServletRequestBuilder register(Long id, Long grantee, String permission) {
        return postRequest(id.toString()).content(body(grantee, permission));
    }

    private String body(Long grantee, String permission) {
        return "{\"granteeUserId\":" + grantee + ",\"permission\":\"" + permission + "\"}";
    }
}
