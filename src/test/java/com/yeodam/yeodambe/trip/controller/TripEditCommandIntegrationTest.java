package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.service.TripEditAccessService;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.TripEditCommandRecordService;
import com.yeodam.yeodambe.trip.service.TripEditLock;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.websocket.internal-service-token=edit-test-token",
        "app.trip.edit-lock.enabled=true",
        "app.trip.edit-lock.namespace=trip-edit-admission-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripEditCommandIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripShareUserRepository shares;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private LoginSessionStore sessions;
    @Autowired private CsrfTokenStore csrfTokens;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired private UserStatsRepository stats;
    @MockitoSpyBean private TripEditNotificationOutboxRepository notifications;
    @MockitoSpyBean private TripEditLock editLock;
    @MockitoSpyBean private TripEditCommandRecordService records;
    @MockitoSpyBean private TripEditAccessService access;
    private Long ownerId;
    private Long readerId;
    private Trip trip;
    private String csrfContext;

    @BeforeEach
    void fixture() {
        csrfContext = UUID.randomUUID().toString();
        csrfTokens.save(csrfContext, "edit-test-csrf");
        ownerId = users.save(new User(UUID.randomUUID() + "@edit.test", "소유자")).getUserId();
        readerId = users.save(new User(UUID.randomUUID() + "@edit.test", "공유자")).getUserId();
        trip = new Trip(ownerId, "원래여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trip = trips.saveAndFlush(trip);
        regions.saveAndFlush(new TripRegion(trip, "11000", "서울", new BigDecimal("37.5"), new BigDecimal("127.0")));
    }

    @Test
    void 기간을_수정한_공유_명령도_저장된_기간을_참여자_알림에_담는다() throws Exception {
        update(ownerId, "{\"requestId\":\"end-only\",\"changes\":{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-04\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        assertThat(trips.findById(trip.getId()).orElseThrow().getEndDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        String endDate = jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.changes.endDate'))
                FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?
                """, String.class, trip.getId());
        String startDate = jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.changes.startDate'))
                FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?
                """, String.class, trip.getId());
        assertThat(endDate).isEqualTo("2026-10-04");
        assertThat(startDate).isEqualTo("2026-10-01");
    }

    @Test
    void 같은_자동저장_요청은_결과를_재사용하고_변경_번호를_다시_증가시키지_않는다() throws Exception {
        String request = "{\"requestId\":\"save\",\"changes\":{\"tripName\":\"바뀐여행\"}}";
        update(ownerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_UPDATE_SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        update(ownerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE user_id = ?",
                Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    void READ의_수정은_거절_결과로_기록하고_여행은_보존한다() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, readerId,
                TripSharePermission.READ, now.plusHours(1), now));
        update(readerId, "{\"requestId\":\"read\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("원래여행");
    }

    @Test
    void READ_수정_거절도_여행_락_안에서_기록한다() throws Exception {
        share(TripSharePermission.READ);

        update(readerId, "{\"requestId\":\"before-lock-read\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(editLock).execute(eq(trip.getId()), any());
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(1);
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 공유_없는_사진_삭제_거절도_여행_락_안에서_기록한다() throws Exception {
        delete(readerId, "{\"requestId\":\"before-lock-no-share\",\"attachmentIds\":[1]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(editLock).execute(eq(trip.getId()), any());
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(1);
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 권한_승인_후_락_진입_시_공유가_만료되어도_수정한다() throws Exception {
        share(TripSharePermission.WRITE);
        doAnswer(invocation -> {
            jdbc.update("UPDATE trip_share_users SET expires_at = ? WHERE trip_id = ?",
                    LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(1), trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        update(readerId, "{\"requestId\":\"admitted-expiry\",\"changes\":{\"tripName\":\"승인된수정\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        update(readerId, "{\"requestId\":\"next-after-expiry\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(editLock, times(2)).execute(eq(trip.getId()), any());
        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("승인된수정");
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @Test
    void 권한_승인_후_락_진입_시_READ로_변경되어도_사진을_삭제한다() throws Exception {
        share(TripSharePermission.WRITE);
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        TripAttachment photo = photo("admitted-delete");
        doAnswer(invocation -> {
            jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        String request = "{\"requestId\":\"admitted-delete\",\"attachmentIds\":[" + photo.getId() + "]}";
        delete(readerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        delete(readerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sequence").value(1));

        verify(editLock, times(2)).execute(eq(trip.getId()), any());
        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\uD800", "\\uDC00", "😀\\uDC00"})
    void 공유_편집도_짝이_깨진_서로게이트_여행명을_거절한다(String escapedName) throws Exception {
        String body = """
                {"requestId":"invalid-surrogate","changes":{"tripName":"%s"}}
                """.formatted(escapedName);

        update(ownerId, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_REQUEST"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("원래여행");
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 권한_승인_후라도_락_진입_전에_삭제된_여행은_수정하지_않는다() throws Exception {
        doAnswer(invocation -> {
            jdbc.update("UPDATE trips SET deleted_at = ? WHERE trip_id = ?", LocalDateTime.now(), trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        update(ownerId, "{\"requestId\":\"deleted-after-admission\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("원래여행");
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 권한_승인_후_락_진입_전에_처리_상태가_바뀌면_수정을_거절한다() throws Exception {
        doAnswer(invocation -> {
            jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        update(ownerId, "{\"requestId\":\"processing-after-admission\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_UPDATE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("원래여행");
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 권한_승인_후_바뀐_기간은_이름만_수정할_때_보존한다() throws Exception {
        LocalDate changedStart = LocalDate.of(2026, 10, 10);
        LocalDate changedEnd = LocalDate.of(2026, 10, 12);
        doAnswer(invocation -> {
            jdbc.update("UPDATE trips SET start_date = ?, end_date = ? WHERE trip_id = ?",
                    changedStart, changedEnd, trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        update(ownerId, "{\"requestId\":\"preserve-latest-dates\",\"changes\":{\"tripName\":\"새이름\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        Trip updated = trips.findById(trip.getId()).orElseThrow();
        assertThat(updated.getStartDate()).isEqualTo(changedStart);
        assertThat(updated.getEndDate()).isEqualTo(changedEnd);
    }

    @Test
    void 동일한_권한_거절이_동시에_저장되어도_확정된_결과를_반환한다() throws Exception {
        share(TripSharePermission.READ);
        String request = "{\"requestId\":\"concurrent-denied\",\"changes\":{\"tripName\":\"금지변경\"}}";
        CountDownLatch checked = new CountDownLatch(2);
        doAnswer(invocation -> {
            checked.countDown();
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(access).requireWritePermission(trip.getId(), readerId);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResultActions> first = executor.submit(() -> update(readerId, request));
            Future<ResultActions> second = executor.submit(() -> update(readerId, request));
            first.get(10, TimeUnit.SECONDS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
            second.get(10, TimeUnit.SECONDS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        }

        verify(editLock, times(2)).execute(eq(trip.getId()), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE user_id = ?",
                Integer.class, readerId)).isEqualTo(1);
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(2);
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 승인된_요청과_경합한_재요청은_미접수_후_확정된_결과를_재사용한다() throws Exception {
        share(TripSharePermission.WRITE);
        String request = "{\"requestId\":\"admission-race\",\"changes\":{\"tripName\":\"금지변경\"}}";
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(invocation -> {
            Optional<?> previous = (Optional<?>) invocation.callRealMethod();
            if (previous.isEmpty() && paused.compareAndSet(false, true)) {
                checked.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return previous;
        }).when(records).findResult(eq(readerId), eq("admission-race"), eq(trip.getId()),
                eq(CommandType.UPDATE_TRIP), any(), any());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResultActions> admitted = executor.submit(() -> update(readerId, request));
            try {
                assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", trip.getId());
                update(readerId, request).andExpect(status().isTooManyRequests());
            } finally {
                proceed.countDown();
            }
            admitted.get(10, TimeUnit.SECONDS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
            update(readerId, request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        }

        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("금지변경");
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(2);
    }

    @Test
    void 서비스_토큰_없이_내부_명령을_호출할_수_없다() throws Exception {
        mvc.perform(post(path()).with(jwt().jwt(token -> token.subject(ownerId.toString())))
                        .cookie(new Cookie("CSRF_CONTEXT", csrfContext))
                        .header("X-CSRF-TOKEN", "edit-test-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"blocked\",\"changes\":{\"tripName\":\"변경\"}}"))
                .andExpect(status().isForbidden());
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void WRITE는_수정하고_재시도는_개인결과만_추가한다() throws Exception {
        share(TripSharePermission.WRITE);
        String body = "{\"requestId\":\"write\",\"changes\":{\"tripName\":\"공유수정\"}}";
        update(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        update(readerId, body).andExpect(status().isOk());
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM trip_edit_notification_outbox WHERE target_type = 'USER' AND target_id = ?",
                Integer.class, readerId)).isEqualTo(1);
    }

    @Test
    void 거절후_권한이_상승해도_같은_요청은_다시_실행하지_않는다() throws Exception {
        share(TripSharePermission.READ);
        String body = "{\"requestId\":\"denied\",\"changes\":{\"tripName\":\"공유수정\"}}";
        update(readerId, body).andExpect(jsonPath("$.data.status").value("REJECTED"));
        jdbc.update("UPDATE trip_share_users SET permission = 'WRITE' WHERE trip_id = ?", trip.getId());
        update(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 잘못된_변경도_결과를_기록하고_본문이_달라지면_충돌이다() throws Exception {
        update(ownerId, "{\"requestId\":\"invalid\",\"changes\":{\"startDate\":\"2026-10-01\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_TRIP_REQUEST"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        update(ownerId, "{\"requestId\":\"invalid\",\"changes\":{\"tripName\":\"새이름\"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_MISMATCH"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 객체_필드_순서만_달라진_요청은_같은_명령이다() throws Exception {
        update(ownerId, "{\"requestId\":\"order\",\"changes\":{\"tripName\":\"순서검증\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-03\"}}")
                .andExpect(status().isOk());
        update(ownerId, "{\"requestId\":\"order\",\"changes\":{\"endDate\":\"2026-10-03\",\"startDate\":\"2026-10-01\",\"tripName\":\"순서검증\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @Test
    void 만료된_요청은_다시_실행하지_않는다() throws Exception {
        String body = "{\"requestId\":\"expired\",\"changes\":{\"tripName\":\"만료검증\"}}";
        update(ownerId, body).andExpect(status().isOk());
        jdbc.update("UPDATE trip_edit_command_records SET result_expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1), ownerId);
        update(ownerId, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_RESULT_EXPIRED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
    }

    @Test
    void 변경이_없으면_공유_이벤트와_변경번호를_늘리지_않는다() throws Exception {
        update(ownerId, "{\"requestId\":\"noop\",\"changes\":{\"tripName\":\"원래여행\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(0));
        assertThat(notificationCount("TRIP_EVENT")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE event_type = 'COMMAND_RESULT' AND target_id = ?",
                Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    void Outbox_저장_실패는_여행과_결과_기록을_함께_롤백한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("test outbox unavailable"))
                .when(notifications).saveAndFlush(any());
        update(ownerId, "{\"requestId\":\"rollback\",\"changes\":{\"tripName\":\"실패변경\"}}")
                .andExpect(status().isServiceUnavailable());
        Trip unchanged = trips.findById(trip.getId()).orElseThrow();
        assertThat(unchanged.getTripName()).isEqualTo("원래여행");
        assertThat(unchanged.getSequence()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE user_id = ?",
                Integer.class, ownerId)).isZero();
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 실제_로그인_쿠키와_CSRF로_내부_명령을_처리한다() throws Exception {
        String sid = UUID.randomUUID().toString();
        String context = UUID.randomUUID().toString();
        sessions.save(sid, ownerId, UUID.randomUUID().toString());
        csrfTokens.save(context, "csrf-value");
        mvc.perform(post(path()).cookie(new Cookie("accessToken", tokens.issue(ownerId, sid)),
                        new Cookie("CSRF_CONTEXT", context))
                        .header("X-Internal-Service-Token", "edit-test-token")
                        .header("X-CSRF-TOKEN", "csrf-value").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cookie\",\"changes\":{\"tripName\":\"쿠키편집\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void 공유_READ_접근_검사는_사용자와_유효기간을_반환한다() throws Exception {
        share(TripSharePermission.READ);
        mvc.perform(get("/internal/websocket/trips/" + trip.getId() + "/access")
                        .with(jwt().jwt(token -> token.subject(readerId.toString())))
                        .header("X-Internal-Service-Token", "edit-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("TRIP_WEBSOCKET_ACCESS_FOUND"))
                .andExpect(jsonPath("$.data.userId").value(readerId))
                .andExpect(jsonPath("$.data.permission").value("READ"))
                .andExpect(jsonPath("$.data.accessValidUntil").isNotEmpty());
    }

    @Test
    void WRITE_일괄삭제와_재시도는_사진_통계를_한번만_변경한다() throws Exception {
        share(TripSharePermission.WRITE);
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        TripAttachment first = photo("first");
        TripAttachment second = photo("second");
        String body = "{\"requestId\":\"delete\",\"attachmentIds\":[" + first.getId() + "," + second.getId() + "]}";
        delete(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PHOTOS_DELETE_SUCCESS"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        delete(readerId, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(attachments.findById(first.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(files.findById(first.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(stats.findByUser_UserId(ownerId).orElseThrow().getStorageUsedBytes()).isZero();
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        String json = jdbc.queryForObject("SELECT event_json FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                String.class, trip.getId());
        assertThat(json).contains("TRIP_PHOTOS_DELETED", "\"attachmentCount\":0").doesNotContain("original/");
    }

    @Test
    void 중복_삭제_ID는_기록된_거절이며_사진은_유지한다() throws Exception {
        TripAttachment photo = photo("duplicate");
        delete(ownerId, "{\"requestId\":\"bad-delete\",\"attachmentIds\":[" + photo.getId() + "," + photo.getId() + "]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_IDS"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%d.9", "\"%d\"", "9223372036854775808"})
    void 정수가_아닌_사진_ID는_삭제하지_않고_원문이_다르면_충돌한다(String idTemplate) throws Exception {
        TripAttachment photo = photo("invalid-id");
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        String invalidId = idTemplate.formatted(photo.getId());
        String invalidBody = "{\"requestId\":\"invalid-id\",\"attachmentIds\":[" + invalidId + "]}";

        delete(ownerId, invalidBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_IDS"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        delete(ownerId, invalidBody).andExpect(jsonPath("$.data.status").value("REJECTED"));
        delete(ownerId, "{\"requestId\":\"invalid-id\",\"attachmentIds\":[" + photo.getId() + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_MISMATCH"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void WRITE의_단일복구는_한번만_적용하고_결과와_여행_이벤트를_재사용한다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        TripAttachment photo = restorablePhoto("shared-restore", place);
        String body = restoreBody("restore-once", photo.getId(), place.getId());

        restore(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_RESTORE_SUCCESS"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        restore(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(1));
        restore(readerId, restoreBody("restore-once", photo.getId(), place.getId() + 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_MISMATCH"));
        jdbc.update("UPDATE trip_edit_command_records SET result_expires_at = ? WHERE user_id = ? AND request_id = ?",
                LocalDateTime.now().minusMinutes(1), readerId, "restore-once");
        restore(readerId, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_RESULT_EXPIRED"));

        assertThat(attachments.findById(photo.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.ACTIVE);
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(2);
        String event = jdbc.queryForObject("SELECT event_json FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                String.class, trip.getId());
        assertThat(event).contains("TRIP_PHOTOS_RESTORED", "\"sourceIssue\":\"BLURRY\"");
    }

    @Test
    void READ와_미공유_사용자는_사진을_복구하지_못한다() throws Exception {
        share(TripSharePermission.READ);
        TripDetailPlace place = place();
        TripAttachment photo = restorablePhoto("read-restore", place);
        restore(readerId, restoreBody("read-restore", photo.getId(), place.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        Long stranger = users.saveAndFlush(new User(UUID.randomUUID() + "@edit.test", "미공유")).getUserId();
        restore(stranger, restoreBody("stranger-restore", photo.getId(), place.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));

        assertThat(attachments.findById(photo.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 만료되거나_READ로_바뀐_공유자는_새_복구를_실행하지_못한다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        TripAttachment photo = restorablePhoto("expired-restore", place);
        jdbc.update("UPDATE trip_share_users SET expires_at = ? WHERE trip_id = ?",
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(1), trip.getId());
        restore(readerId, restoreBody("expired-restore", photo.getId(), place.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
        jdbc.update("UPDATE trip_share_users SET expires_at = ?, permission = 'READ' WHERE trip_id = ?",
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusHours(1), trip.getId());
        restore(readerId, restoreBody("read-after-expiry", photo.getId(), place.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void WRITE_승인_후_락_대기_중_READ가_되어도_복구를_완료한다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        TripAttachment photo = restorablePhoto("admitted-restore", place);
        doAnswer(invocation -> {
            jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", trip.getId());
            return invocation.callRealMethod();
        }).when(editLock).execute(eq(trip.getId()), any());

        restore(readerId, restoreBody("admitted-restore", photo.getId(), place.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        restore(readerId, restoreBody("next-restore", photo.getId(), place.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.ACTIVE);
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @Test
    void 잘못된_복구_입력은_기록하고_일괄복구_실패는_전부_롤백한다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        TripAttachment first = restorablePhoto("bulk-first", place);
        TripAttachment second = restorablePhoto("bulk-second", place);
        restore(readerId, "{\"requestId\":\"invalid-restore\",\"items\":[{\"tripAttachmentId\":\""
                + first.getId() + "\",\"tripPlaceId\":" + place.getId() + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        restore(readerId, "{\"requestId\":\"missing-destination\",\"items\":[{\"tripAttachmentId\":"
                + first.getId() + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("RESTORE_PLACE_FOLDER_REQUIRED"));
        restore(readerId, restoreBody("missing-photo", Long.MAX_VALUE, place.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("ATTACHMENT_NOT_FOUND"));
        TripDetailPlace otherPlace = place();
        restore(readerId, restoreBody("wrong-place", first.getId(), otherPlace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("RESTORE_PLACE_MISMATCH"));
        restore(readerId, "{\"requestId\":\"bulk-failed\",\"items\":[{\"tripAttachmentId\":"
                + first.getId() + ",\"tripPlaceId\":" + place.getId() + "},{\"tripAttachmentId\":"
                + second.getId() + ",\"tripPlaceId\":" + (place.getId() + 1000000L) + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("BULK_RESTORE_FAILED"));

        assertThat(attachments.findById(first.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 미분류_사진도_기존_삭제_명령으로_삭제한다() throws Exception {
        share(TripSharePermission.WRITE);
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        TripDetailPlace place = place();
        TripAttachment photo = restorablePhoto("unclassified-delete", place);

        delete(readerId, "{\"requestId\":\"delete-unclassified\",\"attachmentIds\":[" + photo.getId() + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PHOTOS_DELETE_SUCCESS"));

        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(places.findById(place.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
    }

    @Test
    void 일반과_미분류_사진을_함께_삭제하면_일반_마지막_장소만_삭제한다() throws Exception {
        share(TripSharePermission.WRITE);
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        TripDetailPlace normalPlace = place();
        TripDetailPlace unclassifiedPlace = place();
        TripAttachment normal = activePhotoAtPlace("normal-delete", normalPlace);
        TripAttachment unclassified = restorablePhoto("unclassified-bulk-delete", unclassifiedPlace);

        delete(readerId, "{\"requestId\":\"mixed-delete\",\"attachmentIds\":[" + normal.getId()
                + "," + unclassified.getId() + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PHOTOS_DELETE_SUCCESS"));

        assertThat(attachments.findById(normal.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(attachments.findById(unclassified.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(places.findById(normalPlace.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(places.findById(unclassifiedPlace.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void WRITE는_이백개를_한번에_복구하고_이백한개는_거절한다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        List<TripAttachment> photos = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            photos.add(restorablePhoto("restore-200-" + index, place));
        }
        String items = photos.stream()
                .map(photo -> "{\"tripAttachmentId\":" + photo.getId() + ",\"tripPlaceId\":" + place.getId() + "}")
                .collect(Collectors.joining(","));
        restore(readerId, "{\"requestId\":\"restore-201\",\"items\":[" + items
                + ", {\"tripAttachmentId\":99999999,\"tripPlaceId\":" + place.getId() + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        restore(readerId, "{\"requestId\":\"restore-200\",\"items\":[" + items + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("BULK_ATTACHMENT_RESTORE_SUCCESS"))
                .andExpect(jsonPath("$.data.sequence").value(1));

        assertThat(photos).allSatisfy(photo -> assertThat(attachments.findById(photo.getId()).orElseThrow()
                .getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE));
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @Test
    void 중복_ID와_다른_여행의_사진이_섞인_복구는_적용하지_않는다() throws Exception {
        share(TripSharePermission.WRITE);
        TripDetailPlace place = place();
        TripAttachment own = restorablePhoto("same-trip", place);
        restore(readerId, "{\"requestId\":\"duplicate-restore\",\"items\":[{\"tripAttachmentId\":"
                + own.getId() + ",\"tripPlaceId\":" + place.getId() + "},{\"tripAttachmentId\":"
                + own.getId() + ",\"tripPlaceId\":" + place.getId() + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_REQUEST"));

        Trip otherTrip = new Trip(ownerId, "다른여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(otherTrip, "processingStatus", ProcessingStatus.COMPLETED);
        otherTrip = trips.saveAndFlush(otherTrip);
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(ownerId, "other.jpg", "original/" + UUID.randomUUID(), "image/jpeg"));
        TripAttachment foreign = TripAttachment.initial(otherTrip.getId(), file.getId(), "analyze/other", "preview/other");
        foreign.unclassify(place.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, LocalDateTime.now(),
                BigDecimal.ZERO, BigDecimal.ZERO, 90);
        foreign = attachments.saveAndFlush(foreign);

        restore(readerId, "{\"requestId\":\"mixed-trip\",\"items\":[{\"tripAttachmentId\":"
                + own.getId() + ",\"tripPlaceId\":" + place.getId() + "},{\"tripAttachmentId\":"
                + foreign.getId() + ",\"tripPlaceId\":" + place.getId() + "}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("BULK_RESTORE_FAILED"));
        assertThat(attachments.findById(own.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    private void share(TripSharePermission permission) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, readerId, permission, now.plusHours(1), now));
    }

    private TripAttachment photo(String key) {
        StoredFile file = StoredFile.uploaded(ownerId, key + ".jpg", "original/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(10L);
        files.saveAndFlush(file);
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        photo.classify(null, RegionOrigin.EXIF, LocalDateTime.now(), new BigDecimal("37.5"), new BigDecimal("127.0"), 90);
        return attachments.saveAndFlush(photo);
    }

    private TripDetailPlace place() {
        LocalDateTime now = LocalDateTime.now();
        return places.saveAndFlush(TripDetailPlace.fromAnalysis(trip.getId(), 1, "복구 장소",
                BigDecimal.ZERO, BigDecimal.ZERO, now, now, null));
    }

    private TripAttachment restorablePhoto(String key, TripDetailPlace place) {
        TripAttachment photo = photo(key);
        photo.unclassify(place.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, LocalDateTime.now(),
                new BigDecimal("37.5"), new BigDecimal("127.0"), 90);
        return attachments.saveAndFlush(photo);
    }

    private TripAttachment activePhotoAtPlace(String key, TripDetailPlace place) {
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(ownerId, key + ".jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        photo.classify(place.getId(), RegionOrigin.EXIF, LocalDateTime.now(),
                new BigDecimal("37.5"), new BigDecimal("127.0"), 90);
        return attachments.saveAndFlush(photo);
    }

    private String restoreBody(String requestId, Long photoId, Long placeId) {
        return "{\"requestId\":\"" + requestId + "\",\"items\":[{\"tripAttachmentId\":" + photoId
                + ",\"tripPlaceId\":" + placeId + "}]}";
    }

    private ResultActions restore(Long actorId, String body) throws Exception {
        return mvc.perform(post("/internal/websocket/trips/" + trip.getId() + "/attachments/restore")
                .with(jwt().jwt(token -> token.subject(actorId.toString())))
                .cookie(new Cookie("CSRF_CONTEXT", csrfContext))
                .header("X-CSRF-TOKEN", "edit-test-csrf")
                .header("X-Internal-Service-Token", "edit-test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int notificationCount(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE event_type = ? AND target_id = ?",
                Integer.class, type, "TRIP_EVENT".equals(type) ? trip.getId() : readerId);
    }

    private ResultActions delete(Long actorId, String body) throws Exception {
        return mvc.perform(post("/internal/websocket/trips/" + trip.getId() + "/attachments/delete")
                .with(jwt().jwt(token -> token.subject(actorId.toString())))
                .cookie(new Cookie("CSRF_CONTEXT", csrfContext))
                .header("X-CSRF-TOKEN", "edit-test-csrf")
                .header("X-Internal-Service-Token", "edit-test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(Long actorId, String body) throws Exception {
        return mvc.perform(post(path()).with(jwt().jwt(token -> token.subject(actorId.toString())))
                .cookie(new Cookie("CSRF_CONTEXT", csrfContext))
                .header("X-CSRF-TOKEN", "edit-test-csrf")
                .header("X-Internal-Service-Token", "edit-test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String path() {
        return "/internal/websocket/trips/" + trip.getId() + "/update";
    }
}
