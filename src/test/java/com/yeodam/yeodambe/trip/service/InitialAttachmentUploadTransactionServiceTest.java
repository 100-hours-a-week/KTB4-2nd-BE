package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadTransactionServiceTest {
    @Autowired private InitialAttachmentUploadTransactionService service;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private User owner;
    private Trip trip;
    private InitialAttachmentUploadBatch batch;

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "완료회원"));
        trip = trips.save(new Trip(owner.getUserId(), "완료여행", LocalDate.now(), LocalDate.now()));
        batch = batches.save(new InitialAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                trip.getId(), owner.getUserId(), 1, 12, false));
    }

    @Test
    void commitsProcessingStateBeforeReturningToCaller() {
        var result = start();

        assertThat(result.getId()).isEqualTo(batch.getId());
        assertThat(result.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void refusesProcessingButAllowsCleanedFailedBatchToRestart() {
        start();
        assertThatThrownBy(this::start)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(storedStatus()).isEqualTo("PROCESSING");

        setStatus("FAILED");
        start();
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void returnsCompletedBatchWithoutRestartEvenWhenTripIsCompleted() {
        setStatus("COMPLETED");
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());

        var result = start();

        assertThat(result.getId()).isEqualTo(batch.getId());
        assertThat(result.getStatus()).isEqualTo(InitialAttachmentUploadStatus.COMPLETED);
        assertThat(storedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsMissingUnknownOrMismatchedIdentifiers() {
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), null))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), " "))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), other.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripNotFoundException.class);
        var otherTrip = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        assertThatThrownBy(() -> service.startProcessing(otherTrip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    @Test
    void rejectsNonProcessingOrDeletedTripAndPreservesPendingBatch() {
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(this::start)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        jdbcTemplate.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(this::start).isInstanceOf(TripNotFoundException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    @Test
    void onlyOneOfTwoConcurrentRequestsStartsProcessing() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try {
                    start();
                    return true;
                } catch (TripInitialAttachmentUploadNotAllowedException expected) {
                    return false;
                }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }

            assertThat(new Boolean[]{first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
            assertThat(storedStatus()).isEqualTo("PROCESSING");
        }
    }

    @Test
    void savesOriginalsAttachmentsMetadataAndItemLinksInFileOrder() {
        addItems();
        start();
        var takenAt = OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
        var results = List.of(
                new DerivedPhotoKeys("key-one", "analyze-one", "preview-one", takenAt,
                        new BigDecimal("35.1"), new BigDecimal("129.1"), "camera"),
                new DerivedPhotoKeys("key-two", "analyze-two", "preview-two", "display-two",
                        null, null, null, null));

        var saved = service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), results);

        assertThat(saved).hasSize(2);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .extracting(InitialAttachmentUploadItem::getTripAttachmentId)
                .containsExactly(saved.get(0).getId(), saved.get(1).getId());
        var original = jdbcTemplate.queryForMap(
                "SELECT user_id, original_file_name, object_key, mime_type, upload_status FROM files WHERE file_id = ?",
                saved.get(0).getFileId());
        assertThat(original).containsEntry("user_id", owner.getUserId())
                .containsEntry("original_file_name", "same.jpg")
                .containsEntry("object_key", "key-one")
                .containsEntry("mime_type", "image/jpeg")
                .containsEntry("upload_status", "READY");
        var attachment = jdbcTemplate.queryForMap(
                "SELECT analyze_storage_key, preview_storage_key, latitude, longitude, device_model, taken_at FROM trip_attachments WHERE trip_attachment_id = ?",
                saved.get(0).getId());
        assertThat(attachment).containsEntry("analyze_storage_key", "analyze-one")
                .containsEntry("preview_storage_key", "preview-one")
                .containsEntry("device_model", "camera");
        assertThat((BigDecimal) attachment.get("latitude")).isEqualByComparingTo("35.1");
        assertThat((BigDecimal) attachment.get("longitude")).isEqualByComparingTo("129.1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT taken_at FROM trip_attachments WHERE trip_attachment_id = ?",
                java.sql.Timestamp.class, saved.get(0).getId()).toLocalDateTime())
                .isEqualTo(takenAt.toLocalDateTime());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, saved.get(1).getId())).isEqualTo("display-two");
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void refusesRepeatedSaveWithoutCreatingAdditionalRows() {
        addItems();
        start();
        service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());

        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), owner.getUserId(), batch.getUploadId(), validResults()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);

        assertRowCounts(2);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .allSatisfy(item -> assertThat(item.getTripAttachmentId()).isNotNull());
    }

    @Test
    void rollsBackFirstPhotoAndItsLinkWhenSecondOriginalDoesNotMatch() {
        addItems();
        start();
        var invalid = List.of(validResults().getFirst(),
                new DerivedPhotoKeys("wrong-key", "analyze-two", "preview-two"));

        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), owner.getUserId(), batch.getUploadId(), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("원본과 파생 사진의 순서가 다릅니다.");

        assertRowCounts(0);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .allSatisfy(item -> assertThat(item.getTripAttachmentId()).isNull());
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void rejectsWrongStateOwnerAndResultCountBeforePersisting() {
        addItems();
        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), owner.getUserId(), batch.getUploadId(), validResults()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        start();
        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), owner.getUserId(), batch.getUploadId(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), other.getUserId(), batch.getUploadId(), validResults()))
                .isInstanceOf(TripNotFoundException.class);
        jdbcTemplate.update("UPDATE trips SET processing_status = 'FAILED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> service.saveAttachments(
                trip.getId(), owner.getUserId(), batch.getUploadId(), validResults()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertRowCounts(0);
    }

    @Test
    void commitsBatchCompletionAfterEveryItemIsLinkedAndLeavesTripProcessing() {
        addItems();
        start();
        service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());

        service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId());

        assertThat(storedStatus()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM trips WHERE trip_id = ?", String.class, trip.getId()))
                .isEqualTo("PROCESSING");
        assertRowCounts(2);
    }

    @Test
    void rejectsEmptyUnlinkedAndPartiallyLinkedBatches() {
        start();
        assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        addItems();
        assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        var saved = service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());
        jdbcTemplate.update("UPDATE initial_attachment_upload_items SET trip_attachment_id = NULL WHERE upload_batch_id = ? AND file_order = 2",
                batch.getId());

        assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(storedStatus()).isEqualTo("PROCESSING");
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .extracting(InitialAttachmentUploadItem::getTripAttachmentId)
                .containsExactly(saved.getFirst().getId(), null);
    }

    @Test
    void rejectsCompletionForWrongOwnerTripOrBatchState() {
        addItems();
        start();
        service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());
        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.completeBatch(trip.getId(), other.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), "unknown"))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        for (String status : List.of("PENDING", "FAILED", "COMPLETED")) {
            setStatus(status);
            assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
            assertThat(storedStatus()).isEqualTo(status);
        }
        setStatus("PROCESSING");
        jdbcTemplate.update("UPDATE trips SET processing_status = 'FAILED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void removesOnlyFailedBatchReferencesAndKeepsUploadMetadataAndCompletedBatch() {
        addItems();
        start();
        var saved = service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());
        service.completeBatch(trip.getId(), owner.getUserId(), batch.getUploadId());
        var next = batches.save(new InitialAttachmentUploadBatch(UUID.randomUUID().toString(),
                batch.getExecutionId(), trip.getId(), owner.getUserId(), 2, 12, false));
        items.save(new InitialAttachmentUploadItem(next, 1, "next.jpg", "image/jpeg", 1024L, "next-key"));
        service.startProcessing(trip.getId(), owner.getUserId(), next.getUploadId());
        service.saveAttachments(trip.getId(), owner.getUserId(), next.getUploadId(),
                List.of(new DerivedPhotoKeys("next-key", "next-analyze", "next-preview")));

        service.failBatch(trip.getId(), owner.getUserId(), next.getUploadId());

        assertRowCounts(2);
        assertThat(storedStatus()).isEqualTo("COMPLETED");
        assertThat(batches.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(InitialAttachmentUploadStatus.FAILED);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .extracting(InitialAttachmentUploadItem::getTripAttachmentId)
                .containsExactly(saved.get(0).getId(), saved.get(1).getId());
        var remaining = items.findAllByBatch_IdOrderByFileOrderAsc(next.getId());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getTripAttachmentId()).isNull();
        assertThat(remaining.getFirst().getObjectKey()).isEqualTo("next-key");
        assertThat(remaining.getFirst().getSizeBytes()).isEqualTo(1024L);
        assertThat(jdbcTemplate.queryForObject("SELECT processing_status FROM trips WHERE trip_id = ?",
                String.class, trip.getId())).isEqualTo("PROCESSING");
    }

    @Test
    void marksFailureBeforeAnyAttachmentHasBeenStored() {
        addItems();
        start();

        service.failBatch(trip.getId(), owner.getUserId(), batch.getUploadId());

        assertThat(storedStatus()).isEqualTo("FAILED");
        assertRowCounts(0);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId())).hasSize(2)
                .allSatisfy(item -> assertThat(item.getTripAttachmentId()).isNull());
    }

    @Test
    void rejectsFailureCleanupForWrongOwnerStateAndDeletedTripWithoutRemovingRows() {
        addItems();
        start();
        var saved = service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());
        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.failBatch(trip.getId(), other.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripNotFoundException.class);
        for (String status : List.of("PENDING", "COMPLETED", "FAILED")) {
            setStatus(status);
            assertThatThrownBy(() -> service.failBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
            assertThat(storedStatus()).isEqualTo(status);
        }
        setStatus("PROCESSING");
        jdbcTemplate.update("UPDATE trips SET processing_status = 'CANCELED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> service.failBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        jdbcTemplate.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> service.failBatch(trip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripNotFoundException.class);
        assertRowCounts(2);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .extracting(InitialAttachmentUploadItem::getTripAttachmentId)
                .containsExactly(saved.get(0).getId(), saved.get(1).getId());
    }

    @Test
    void rollsBackDeletionLinksAndFailureStateWhenEnclosingTransactionFails() {
        addItems();
        start();
        var saved = service.saveAttachments(trip.getId(), owner.getUserId(), batch.getUploadId(), validResults());
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.failBatch(trip.getId(), owner.getUserId(), batch.getUploadId());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        assertRowCounts(2);
        assertThat(storedStatus()).isEqualTo("PROCESSING");
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()))
                .extracting(InitialAttachmentUploadItem::getTripAttachmentId)
                .containsExactly(saved.get(0).getId(), saved.get(1).getId());
    }

    private void addItems() {
        items.saveAll(List.of(
                new InitialAttachmentUploadItem(batch, 2, "same.jpg", "image/jpeg", 2048L, "key-two"),
                new InitialAttachmentUploadItem(batch, 1, "same.jpg", "image/jpeg", 1024L, "key-one")));
    }

    private List<DerivedPhotoKeys> validResults() {
        return List.of(new DerivedPhotoKeys("key-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("key-two", "analyze-two", "preview-two"));
    }

    private void assertRowCounts(int expected) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM files WHERE user_id = ?",
                Integer.class, owner.getUserId())).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?",
                Integer.class, trip.getId())).isEqualTo(expected);
    }

    private InitialAttachmentUploadBatch start() {
        return service.startProcessing(trip.getId(), owner.getUserId(), batch.getUploadId());
    }

    private String storedStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM initial_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.getId());
    }

    private void setStatus(String status) {
        jdbcTemplate.update(
                "UPDATE initial_attachment_upload_batches SET status = ? WHERE upload_batch_id = ?",
                status, batch.getId());
    }
}
