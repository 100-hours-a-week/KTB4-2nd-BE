package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdditionalAttachmentAdmissionIntegrationTest {
    @Autowired
    private AdditionalAttachmentAdmissionTransactionService admissions;
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private JdbcTemplate jdbc;

    private Long tripId;
    private Long ownerId;
    private Long actorId;

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        actorId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        LocalDateTime now = LocalDateTime.now();
        shares.saveAndFlush(new TripShareUser(
                tripId, ownerId, actorId, TripSharePermission.WRITE, now.plusDays(1), now
        ));
    }

    @Test
    void 서로_다른_접수자의_V3_묶음은_소유자_용량으로_독립_예약한다() {
        admit(ownerId, request(UUID.randomUUID().toString(), 1, 1, true));
        String additionId = UUID.randomUUID().toString();
        admit(actorId, request(additionId, 1, 1, true));

        assertThat(executionCount()).isEqualTo(2L);
        assertThat(reserved(ownerId)).isEqualTo(24L);
        assertThat(reserved(actorId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT accepted_actor_id FROM additional_attachment_executions WHERE execution_id = ?",
                Long.class, additionId)).isEqualTo(actorId);
        assertThat(jdbc.queryForObject(
                "SELECT processing_mode FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, additionId)).isEqualTo("V3");
    }

    @Test
    void 같은_배치의_재접수는_키와_예약량을_재사용한다() {
        AdditionalAttachmentUploadUrlRequest request = request(UUID.randomUUID().toString(), 1, 1, true);
        AdditionalAttachmentUploadBatch first = admit(actorId, request);
        String key = jdbc.queryForObject(
                "SELECT object_key FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                String.class, first.getId());

        AdditionalAttachmentUploadBatch replay = admit(actorId, request);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getUploadId()).isEqualTo(first.getUploadId());
        assertThat(reserved(ownerId)).isEqualTo(12L);
        assertThat(jdbc.queryForObject(
                "SELECT object_key FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                String.class, first.getId())).isEqualTo(key);
    }

    @Test
    void 같은_배치를_동시에_접수해도_파일과_용량은_한번만_예약한다() throws Exception {
        AdditionalAttachmentUploadUrlRequest request = request(UUID.randomUUID().toString(), 1, 1, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<AdditionalAttachmentUploadBatch>> results = IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new TimeoutException();
                        }
                        return admit(actorId, request);
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AdditionalAttachmentUploadBatch first = results.get(0).get(10, TimeUnit.SECONDS);
            AdditionalAttachmentUploadBatch second = results.get(1).get(10, TimeUnit.SECONDS);

            assertThat(first.getId()).isEqualTo(second.getId());
            assertThat(executionCount()).isOne();
            assertThat(reserved(ownerId)).isEqualTo(12L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                    Long.class, first.getId())).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 권한_철회나_READ_변경_후에도_기존_접수자의_다음_배치를_계속한다(boolean revoke) {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadBatch first = admit(actorId, request(additionId, 1, 2, false));
        jdbc.update("UPDATE additional_attachment_upload_batches SET status = 'VERIFIED' WHERE upload_batch_id = ?",
                first.getId());
        changePermission(revoke);

        AdditionalAttachmentUploadBatch second = admit(actorId, request(additionId, 2, 2, true));

        assertThat(second.getBatchNo()).isEqualTo(2);
        assertThat(reserved(ownerId)).isEqualTo(24L);
        assertThat(reserved(actorId)).isZero();
        assertThat(executionCount()).isOne();
    }

    @Test
    void 권한_철회는_새_묶음을_막고_다른_접수자는_기존_묶음을_사용하지_못한다() {
        String additionId = UUID.randomUUID().toString();
        admit(actorId, request(additionId, 1, 2, false));
        changePermission(true);

        assertThatThrownBy(() -> admit(actorId, request(UUID.randomUUID().toString(), 1, 1, true)))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> admit(ownerId, request(additionId, 1, 2, false)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(reserved(ownerId)).isEqualTo(12L);
        assertThat(executionCount()).isOne();
    }

    @Test
    void 대기_묶음의_선언_장수도_여행_200장_한도에_포함한다() {
        admit(ownerId, request(UUID.randomUUID().toString(), 1, 150, false));

        assertThatThrownBy(() -> admit(actorId, request(UUID.randomUUID().toString(), 1, 51, false)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThat(executionCount()).isOne();
        assertThat(reserved(ownerId)).isEqualTo(12L);
    }

    @Test
    void 탈퇴한_접수자는_기존_배치를_재접수할_수_없다() {
        AdditionalAttachmentUploadUrlRequest request = request(UUID.randomUUID().toString(), 1, 1, true);
        admit(actorId, request);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?", actorId);

        assertThatThrownBy(() -> admit(actorId, request)).isInstanceOf(UserNotFoundException.class);
        assertThat(reserved(ownerId)).isEqualTo(12L);
        assertThat(executionCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFIED", "CONVERTING", "PREPARED", "QUEUED", "PROCESSING", "COMPLETED", "FAILED"})
    void 업로드_대기_외의_배치는_재접수를_거부하고_활동_시각을_유지한다(String status) {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlRequest request = request(additionId, 1, 1, true);
        AdditionalAttachmentUploadBatch batch = admit(actorId, request);
        LocalDateTime originalActivity = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update("UPDATE additional_attachment_upload_batches SET status = ? WHERE upload_batch_id = ?",
                status, batch.getId());
        jdbc.update("UPDATE additional_attachment_executions SET last_upload_activity_at = ? WHERE execution_id = ?",
                originalActivity, additionId);

        assertThatThrownBy(() -> admit(actorId, request))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(jdbc.queryForObject(
                "SELECT last_upload_activity_at FROM additional_attachment_executions WHERE execution_id = ?",
                LocalDateTime.class, additionId)).isEqualTo(originalActivity);
        assertThat(reserved(ownerId)).isEqualTo(12L);
    }

    @Test
    void 기존_방식의_묶음은_V3_재접수나_다음_배치로_이어받지_못한다() {
        String additionId = UUID.randomUUID().toString();
        AdditionalAttachmentUploadUrlRequest request = request(additionId, 1, 2, false);
        AdditionalAttachmentUploadBatch first = admit(ownerId, request);
        jdbc.update("""
                UPDATE additional_attachment_executions
                SET processing_mode = 'LEGACY', accepted_actor_id = NULL WHERE execution_id = ?
                """, additionId);
        jdbc.update("UPDATE additional_attachment_upload_batches SET status = 'VERIFIED' WHERE upload_batch_id = ?",
                first.getId());

        assertThatThrownBy(() -> admit(ownerId, request))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> admit(ownerId, request(additionId, 2, 2, true)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(executionCount()).isOne();
        assertThat(reserved(ownerId)).isEqualTo(12L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_batches WHERE trip_id = ?",
                Long.class, tripId)).isOne();
    }

    @Test
    void 소유자_용량이_부족하면_실행과_배치와_예약을_함께_롤백한다() {
        jdbc.update("UPDATE user_stats SET storage_maximum_bytes = 11 WHERE user_id = ?", ownerId);

        assertThatThrownBy(() -> admit(actorId, request(UUID.randomUUID().toString(), 1, 1, true)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThat(executionCount()).isZero();
        assertThat(reserved(ownerId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_batches WHERE trip_id = ?",
                Long.class, tripId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_items item JOIN additional_attachment_upload_batches batch "
                        + "ON batch.upload_batch_id = item.upload_batch_id WHERE batch.trip_id = ?",
                Long.class, tripId)).isZero();
    }

    private AdditionalAttachmentUploadBatch admit(Long actor, AdditionalAttachmentUploadUrlRequest request) {
        return mutations.execute(tripId, () -> admissions.prepareBatch(tripId, actor, request));
    }

    private void changePermission(boolean revoke) {
        if (revoke) {
            jdbc.update("UPDATE trip_share_users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        } else {
            jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", tripId);
        }
    }

    private long executionCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id = ?", Long.class, tripId
        );
    }

    private long reserved(Long userId) {
        return jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, userId);
    }

    private AdditionalAttachmentUploadUrlRequest request(String additionId, int batchNo, int total, boolean last) {
        return new AdditionalAttachmentUploadUrlRequest(additionId, batchNo, total, last, List.of(
                new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)
        ));
    }
}
