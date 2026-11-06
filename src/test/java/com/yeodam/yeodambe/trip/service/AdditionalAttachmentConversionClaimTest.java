package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.user.entity.UserStats;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentConversionClaimTest {
    @Autowired private AdditionalAttachmentUploadTransactionService service;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    private AdditionalAttachmentUploadBatch batch;
    private AdditionalAttachmentExecution execution;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET next_attempt_at = '2099-01-01'");
        var user = new User(UUID.randomUUID() + "@yeodam.test", "claim");
        entityManager.persist(user);
        var stats = new UserStats(user);
        stats.reserveStorage(1024L);
        entityManager.persist(stats);
        var trip = new Trip(user.getUserId(), "claim", LocalDate.now(), LocalDate.now());
        entityManager.persist(trip);
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        entityManager.refresh(trip);
        execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), trip.getId(), user.getUserId(), 1, LocalDateTime.now());
        entityManager.persist(execution);
        execution.reserve(1024L);
        batch = new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(), execution.getExecutionId(),
                trip.getId(), user.getUserId(), 1, 1, true);
        entityManager.persist(batch);
        batch.markVerified();
        entityManager.persist(new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 1024L, "original"));
        entityManager.flush();
    }

    @Test
    void 배치를_점유하고_분리된_값을_반환하며_유효한_점유를_재제공하지_않는다() {
        var claim = service.claimBatch().orElseThrow();
        assertThat(claim.batchId()).isEqualTo(batch.getId());
        assertThat(claim.executionId()).isEqualTo(execution.getExecutionId());
        assertThat(UUID.fromString(claim.workerToken())).isNotNull();
        assertThat(claim.photos()).hasSize(1);
        assertThat(claim.photos().getFirst().objectKey()).isEqualTo("original");
        assertThat(claim.photos().getFirst().derived()).isNull();
        assertThat(service.claimBatch()).isEmpty();
        entityManager.flush();
        entityManager.clear();
        assertThat(claim.photos().getFirst().fileName()).isEqualTo("photo.jpg");
    }

    @Test
    void 만료된_배치를_재점유하고_이전_작업자의_임대_갱신을_거부한다() {
        var first = service.claimBatch().orElseThrow();
        entityManager.flush();
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET lease_expires_at = '2000-01-01' "
                + "WHERE upload_batch_id = ?", batch.getId());
        var second = service.claimBatch().orElseThrow();
        assertThat(second.workerToken()).isNotEqualTo(first.workerToken());
        assertThat(service.renewLease(first)).isFalse();
        assertThat(service.renewLease(second)).isTrue();
    }

    @Test
    void 실행_종료나_임대_만료_후_갱신을_거부한다() {
        var claim = service.claimBatch().orElseThrow();
        entityManager.flush();
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET lease_expires_at = '2000-01-01' "
                + "WHERE upload_batch_id = ?", batch.getId());
        assertThat(service.renewLease(claim)).isFalse();
        var next = service.claimBatch().orElseThrow();
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, "FAILED", LocalDateTime.now());
        entityManager.flush();
        assertThat(service.renewLease(next)).isFalse();
        assertThat(service.claimBatch()).isEmpty();
    }

    @Test
    void 변환_전에_예정_키를_기록하고_사진을_한_번만_저장한다() {
        var claim = service.claimBatch().orElseThrow();
        Long itemId = claim.photos().getFirst().itemId();
        var keys = service.startPhotoAttempt(claim, itemId);
        assertThat(keys).hasSize(2);
        entityManager.flush();
        var item = entityManager.find(AdditionalAttachmentUploadItem.class, itemId);
        assertThat(item.getConversionAttempts()).isEqualTo(1);
        assertThat(service.cleanupObjects(item)).extracting(AdditionalAttachmentUploadTransactionService.CleanupObject::key)
                .containsExactly("original", keys.get(0), keys.get(1));
        var derived = new DerivedPhotoKeys("original", keys.get(0), keys.get(1), null,
                null, null, null, null, 1024L, 100L, 50L, null);
        service.savePhotoSuccess(claim, itemId, derived);
        service.savePhotoSuccess(claim, itemId, derived);
        entityManager.flush();
        entityManager.clear();
        item = entityManager.find(AdditionalAttachmentUploadItem.class, itemId);
        assertThat(item.getDerivedJson()).isNotNull();
        assertThat(item.getTripAttachmentId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?",
                Integer.class, claim.tripId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, claim.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT storage_used_bytes FROM user_stats WHERE user_id = ?",
                Long.class, claim.userId())).isEqualTo(1024L);
        assertThat(entityManager.find(AdditionalAttachmentExecution.class, claim.executionId()).getReservedBytes()).isZero();
        assertThat(entityManager.find(AdditionalAttachmentExecution.class, claim.executionId()).getAccountedBytes())
                .isEqualTo(1024L);
        assertThatThrownBy(() -> service.startPhotoAttempt(claim, itemId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 잘못된_출력_키와_이전_작업자의_사진_저장을_거부한다() {
        var first = service.claimBatch().orElseThrow();
        Long itemId = first.photos().getFirst().itemId();
        service.startPhotoAttempt(first, itemId);
        var wrong = new DerivedPhotoKeys("original", "wrong", "wrong-preview", null,
                null, null, null, null, 1024L, 100L, 50L, null);
        assertThatThrownBy(() -> service.savePhotoSuccess(first, itemId, wrong))
                .isInstanceOf(IllegalStateException.class);
        entityManager.flush();
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET lease_expires_at = '2000-01-01' "
                + "WHERE upload_batch_id = ?", batch.getId());
        var second = service.claimBatch().orElseThrow();
        var keys = service.startPhotoAttempt(second, itemId);
        var derived = new DerivedPhotoKeys("original", keys.get(0), keys.get(1), null,
                null, null, null, null, 1024L, 100L, 50L, null);
        assertThatThrownBy(() -> service.savePhotoSuccess(first, itemId, derived))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(keys.getFirst()).contains("/2/");
        var item = entityManager.find(AdditionalAttachmentUploadItem.class, itemId);
        assertThat(service.cleanupObjects(item).stream()
                .filter(object -> object.key().endsWith("/1/analyze.jpg")
                        || object.key().endsWith("/1/preview.webp")).toList())
                .allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
    }

    @Test
    void 완료시_입력을_닫고_반복_완료시_결과_상태를_유지한다() {
        service.markVerified(batch.getTripId(), batch.getUserId(), batch.getUploadId());
        assertThat(execution.isInputClosed()).isTrue();
        execution.receiveResult("{}", "COMPLETED", LocalDateTime.now());
        service.markVerified(batch.getTripId(), batch.getUserId(), batch.getUploadId());
        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);
    }

    @Test
    void 영구_변환_실패시_새_사진만_삭제_처리하고_예약을_해제한다() {
        var claim = service.claimBatch().orElseThrow();
        var keys = service.startPhotoAttempt(claim, claim.photos().getFirst().itemId());
        service.retryBatch(claim, claim.photos().getFirst().itemId(), true);
        entityManager.flush();
        entityManager.clear();
        var ended = entityManager.find(AdditionalAttachmentExecution.class, claim.executionId());
        assertThat(ended.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(ended.isReservationReleased()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, claim.userId())).isZero();
        var item = entityManager.find(AdditionalAttachmentUploadItem.class, claim.photos().getFirst().itemId());
        assertThat(service.cleanupObjects(item)).allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
        assertThat(service.claimBatch()).isEmpty();
    }

    @Test
    void 용량_거부시_사진_시도_없이_재시도를_예약한다() {
        var claim = service.claimBatch().orElseThrow();
        service.releaseSaturatedBatch(claim);
        assertThat(service.claimBatch()).isEmpty();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT conversion_attempts FROM additional_attachment_upload_items "
                + "WHERE upload_item_id = ?", Integer.class, claim.photos().getFirst().itemId())).isZero();
    }
}
