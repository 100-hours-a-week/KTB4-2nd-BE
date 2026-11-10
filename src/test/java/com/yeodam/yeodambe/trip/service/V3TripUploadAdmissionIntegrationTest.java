package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadUrlResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class V3TripUploadAdmissionIntegrationTest {
    @Autowired private AdditionalAttachmentUploadUrlService urls;
    @Autowired private AdditionalAttachmentUploadCompletionService completion;
    @Autowired private TripService trips;
    @Autowired private TripUpdateService updates;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripShareUserRepository shares;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;
    @Autowired private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;

    private Long ownerId;
    private Long actorId;
    private Long tripId;

    @BeforeEach
    void prepareTripAndShare() {
        ownerId = createUser();
        actorId = createUser();
        tripId = tripRepository.saveAndFlush(new Trip(ownerId, "동시 편집", LocalDate.now(), LocalDate.now()))
                .getId();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
        LocalDateTime now = LocalDateTime.now();
        shares.saveAndFlush(new TripShareUser(tripId, ownerId, actorId, TripSharePermission.WRITE,
                now.plusDays(1), now));
        when(storage.createUploadUrl(anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(lock().isHeldByCurrentThread()).isFalse();
            return "https://example.test/upload";
        });
        when(storage.size(anyString())).thenReturn(12L);
        when(storage.open(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0
        }));
    }

    @Test
    void 다른_사용자의_추가_묶음을_독립적으로_접수하고_소유자_용량으로_예약한다() {
        urls.issueUploadUrls(tripId, ownerId, request(UUID.randomUUID().toString(), 1, 1, true));
        urls.issueUploadUrls(tripId, actorId, request(UUID.randomUUID().toString(), 1, 1, true));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id = ?
                """, Integer.class, tripId)).isEqualTo(2);
        assertThat(reserved(ownerId)).isEqualTo(24L);
        assertThat(reserved(actorId)).isZero();
    }

    @Test
    void 권한_회수_후에도_같은_접수자의_기존_묶음을_계속한다() {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlResponse first = urls.issueUploadUrls(
                tripId, actorId, request(additionId, 1, 2, false));
        revokeShare();
        completion.complete(tripId, actorId, new AdditionalAttachmentUploadCompleteRequest(first.uploadId()));
        AdditionalAttachmentUploadUrlResponse second = urls.issueUploadUrls(
                tripId, actorId, request(additionId, 2, 2, true));
        completion.complete(tripId, actorId, new AdditionalAttachmentUploadCompleteRequest(second.uploadId()));
        assertThat(jdbc.queryForObject("""
                SELECT accepted_actor_id FROM additional_attachment_executions WHERE execution_id = ?
                """, Long.class, additionId)).isEqualTo(actorId);
        assertThat(jdbc.queryForObject("""
                SELECT input_closed FROM additional_attachment_executions WHERE execution_id = ?
                """, Boolean.class, additionId)).isTrue();
        assertThat(reserved(ownerId)).isEqualTo(24L);
    }

    @Test
    void 다른_접수자와_새_묶음은_권한_회수_예외를_사용하지_못한다() {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlResponse issued = urls.issueUploadUrls(
                tripId, actorId, request(additionId, 1, 2, false));
        revokeShare();
        assertThatThrownBy(() -> urls.issueUploadUrls(tripId, actorId,
                request(UUID.randomUUID().toString(), 1, 1, true))).isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> completion.complete(tripId, ownerId,
                new AdditionalAttachmentUploadCompleteRequest(issued.uploadId())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(reserved(ownerId)).isEqualTo(12L);
    }

    @Test
    void 여러_묶음의_선언_장수도_여행의_200장_한도에_포함한다() {
        urls.issueUploadUrls(tripId, ownerId, request(UUID.randomUUID().toString(), 1, 150, false));
        assertThatThrownBy(() -> urls.issueUploadUrls(tripId, actorId,
                request(UUID.randomUUID().toString(), 1, 51, false)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThat(reserved(ownerId)).isEqualTo(12L);
    }

    @Test
    void 탈퇴한_접수자는_기존_묶음도_계속할_수_없다() {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlResponse first = urls.issueUploadUrls(
                tripId, actorId, request(additionId, 1, 2, false));
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?", actorId);
        assertThatThrownBy(() -> completion.complete(tripId, actorId,
                new AdditionalAttachmentUploadCompleteRequest(first.uploadId())))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> urls.issueUploadUrls(tripId, actorId,
                request(additionId, 2, 2, true))).isInstanceOf(UserNotFoundException.class);
        assertThat(reserved(ownerId)).isEqualTo(12L);
    }

    @Test
    void 추가_접수_중에도_여행_정보를_수정하고_기존_결과를_조회한다() {
        urls.issueUploadUrls(tripId, ownerId, request(UUID.randomUUID().toString(), 1, 1, true));
        updates.updateTrip(tripId, ownerId, new TripUpdateRequest("수정한 여행", null, null, List.of("11000")));
        assertThat(trips.findTripDetail(tripId, ownerId).tripName()).isEqualTo("수정한 여행");
        assertThat(trips.findTripDetailMap(tripId, ownerId).markers()).isEmpty();
        assertThat(trips.findTripForEdit(tripId, ownerId, null).tripName()).isEqualTo("수정한 여행");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ?
                """, Integer.class, tripId)).isZero();
    }

    @Test
    void 전환_전에_접수된_기존_묶음도_원래_모드와_예약을_유지하며_완료한다() {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlResponse first = urls.issueUploadUrls(
                tripId, ownerId, request(additionId, 1, 2, false));
        new TripAnalysisTestFixture(jdbc).markLegacyUpload(first.uploadId());
        assertThat(urls.issueUploadUrls(tripId, ownerId, request(additionId, 1, 2, false)).uploadId())
                .isEqualTo(first.uploadId());
        completion.complete(tripId, ownerId, new AdditionalAttachmentUploadCompleteRequest(first.uploadId()));
        AdditionalAttachmentUploadUrlResponse second = urls.issueUploadUrls(
                tripId, ownerId, request(additionId, 2, 2, true));
        completion.complete(tripId, ownerId, new AdditionalAttachmentUploadCompleteRequest(second.uploadId()));

        assertThat(jdbc.queryForObject(
                "SELECT processing_mode FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, additionId)).isEqualTo("LEGACY");
        assertThat(reserved(ownerId)).isEqualTo(24L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOREIGN", "READ", "OWNER", "INVALID"})
    void 권한과_입력은_락_경합_전에_검사하고_허용된_요청만_경합_오류를_반환한다(String kind) throws Exception {
        Long callerId = actorId;
        Class<? extends RuntimeException> expected = TripEditLockRejectedException.class;
        AdditionalAttachmentUploadUrlRequest request = request(UUID.randomUUID().toString(), 1, 1, true);
        if (kind.equals("FOREIGN")) {
            revokeShare();
            expected = TripNotFoundException.class;
        } else if (kind.equals("READ")) {
            jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", tripId);
            expected = WritePermissionRequiredException.class;
        } else {
            callerId = ownerId;
            if (kind.equals("INVALID")) {
                request = request("invalid", 1, 1, true);
                expected = InvalidAttachmentUploadException.class;
            }
        }
        Long actor = callerId;
        AdditionalAttachmentUploadUrlRequest submitted = request;
        Class<? extends RuntimeException> failure = expected;
        RLock lock = lock();
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> assertThatThrownBy(
                    () -> urls.issueUploadUrls(tripId, actor, submitted)).isInstanceOf(failure));
            response.get(5, TimeUnit.SECONDS);
        } finally {
            lock.unlock();
        }
        verifyNoInteractions(storage);
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    @Test
    void 새_묶음_접수도_현재_여행_참여자에게_전달할_알림을_같이_기록한다() {
        AdditionalAttachmentUploadUrlRequest request =
                new AdditionalAttachmentUploadUrlRequest(
                        UUID.randomUUID().toString(), 1, 1, true, List.of(new AdditionalAttachmentUploadUrlRequest.Attachment(
                                "new.jpg", "image/jpeg", 12L)));
        urls.issueUploadUrls(tripId, ownerId, request);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE target_type = 'TRIP' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.status')) = 'PROCESSING'
                """, Integer.class, tripId)).isPositive();
    }

    private Long createUser() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "편집회원"));
        stats.saveAndFlush(new UserStats(user));
        return user.getUserId();
    }

    private void revokeShare() {
        jdbc.update("UPDATE trip_share_users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
    }

    private long reserved(Long userId) {
        return jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, userId);
    }

    private AdditionalAttachmentUploadUrlRequest request(String additionId, int batchNo, int total, boolean last) {
        return new AdditionalAttachmentUploadUrlRequest(additionId, batchNo, total, last,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)));
    }
}
