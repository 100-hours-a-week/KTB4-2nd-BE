package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UnstartedTripTimeoutIntegrationTest {
    @Autowired
    private TripUploadTimeoutService scheduler;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripCreationExecutionRepository executions;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripProcessingStatusService statusService;
    @Autowired
    private InitialAttachmentUploadTransactionService transactions;
    @Autowired
    private AdditionalAttachmentExecutionRepository additions;
    @Autowired
    private InitialAttachmentUploadBatchRepository initialBatches;
    @Autowired
    private AdditionalAttachmentUploadBatchRepository additionalBatches;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;
    private Trip trip;

    @BeforeEach
    void prepareTrip() {
        owner = users.save(new User(UUID.randomUUID() + "@timeout.test", "미시작"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "미시작 여행", LocalDate.now(), LocalDate.now()));
        jdbc.update("UPDATE trips SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 11 MINUTE WHERE trip_id = ?", trip.getId());
        jdbc.update("UPDATE user_stats SET storage_used_bytes = 123, storage_reserved_bytes = 456 WHERE user_id = ?", owner.getUserId());
    }

    @AfterEach
    void closeTestTrips() {
        jdbc.update("UPDATE trips SET processing_status = 'CANCELED' WHERE user_id = ? AND processing_status = 'PROCESSING'",
                owner.getUserId());
    }

    @Test
    void 여행_생성_시각은_JVM과_시간대가_다른_DB_시각으로_기록한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String originalTimeZone = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
            try {
                jdbc.execute("SET time_zone = '+12:00'");
                LocalDateTime before = transactions.databaseTime();
                Trip created = trips.saveAndFlush(new Trip(
                        owner.getUserId(), "DB 시각", LocalDate.now(), LocalDate.now()));
                LocalDateTime after = transactions.databaseTime();

                assertThat(created.getCreatedAt()).isBetween(before, after);
                transactions.failUnstartedTrip(created.getId());
                assertThat(trips.findById(created.getId()).orElseThrow().getProcessingStatus())
                        .isEqualTo(ProcessingStatus.PROCESSING);
            } finally {
                jdbc.update("SET time_zone = ?", originalTimeZone);
            }
        });
    }

    @Test
    void 실행_기록_없는_오래된_여행은_스케줄러가_실패로_전환한다() {
        scheduler.failExpiredUploads();
        scheduler.failExpiredUploads();

        Trip expired = trips.findById(trip.getId()).orElseThrow();
        UserStats preserved = stats.findByUser_UserId(owner.getUserId()).orElseThrow();
        assertThat(expired.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(expired.getDeletedAt()).isNull();
        assertThat(executions.findByTripId(trip.getId())).isEmpty();
        assertThat(preserved.getStorageUsedBytes()).isEqualTo(123L);
        assertThat(preserved.getStorageReservedBytes()).isEqualTo(456L);
        assertThat(trips.findListLatest(owner.getUserId(), null, null, PageRequest.of(0, 8))).isEmpty();
        assertThat(statusService.findStatus(trip.getId(), owner.getUserId()).status())
                .isEqualTo(TripProcessingStatusResponse.Status.FAILED);
    }

    @Test
    void 생성_후_10분이_지나지_않은_여행은_유지한다() {
        jdbc.update("UPDATE trips SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 9 MINUTE WHERE trip_id = ?", trip.getId());

        assertProtected();
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,0"})
    void 생성_시각은_정확한_10분_경계까지_만료한다(long microsAfterBoundary, int expectedUpdates) {
        LocalDateTime now = transactions.databaseTime();
        LocalDateTime before = now.minusMinutes(10);
        jdbc.update("UPDATE trips SET created_at = ? WHERE trip_id = ?",
                before.plusNanos(microsAfterBoundary * 1_000), trip.getId());

        assertThat(trips.findUnstartedUploadExpired(before, PageRequest.of(0, 100)).contains(trip.getId()))
                .isEqualTo(expectedUpdates == 1);
        Integer updated = new TransactionTemplate(transactionManager).execute(status ->
                trips.failUnstartedUploadIfExpired(trip.getId(), before, now));

        assertThat(updated).isEqualTo(expectedUpdates);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 처리중이_아닌_여행은_변경하지_않는다(ProcessingStatus status) {
        jdbc.update("UPDATE trips SET processing_status = ? WHERE trip_id = ?", status.name(), trip.getId());

        transactions.failUnstartedTrip(trip.getId());

        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(status);
        assertThat(trips.findUnstartedUploadExpired(transactions.databaseTime(), PageRequest.of(0, 100)))
                .doesNotContain(trip.getId());
    }

    @Test
    void 삭제된_여행은_변경하지_않는다() {
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", trip.getId());

        assertProtected();
    }

    @ParameterizedTest
    @EnumSource(RelatedData.class)
    void 관련_자료가_하나라도_있으면_후보와_실제_갱신에서_제외한다(RelatedData relatedData) {
        saveRelatedData(relatedData);
        List<Map<String, Object>> photos = jdbc.queryForList(
                "SELECT trip_attachment_id, deleted_at FROM trip_attachments WHERE trip_id = ?", trip.getId());
        List<Map<String, Object>> storedFiles = jdbc.queryForList(
                "SELECT file_id, deleted_at FROM files WHERE user_id = ?", owner.getUserId());

        assertProtected();

        assertThat(jdbc.queryForList(
                "SELECT trip_attachment_id, deleted_at FROM trip_attachments WHERE trip_id = ?", trip.getId()))
                .isEqualTo(photos);
        assertThat(jdbc.queryForList("SELECT file_id, deleted_at FROM files WHERE user_id = ?", owner.getUserId()))
                .isEqualTo(storedFiles);
    }

    @Test
    void 업로드가_먼저_잠그면_커밋된_실행과_예약을_보존한다() throws Exception {
        CountDownLatch uploaded = new CountDownLatch(1);
        CountDownLatch commitUpload = new CountDownLatch(1);
        CountDownLatch expirationStarted = new CountDownLatch(1);
        assertThat(trips.findUnstartedUploadExpired(transactions.databaseTime(), PageRequest.of(0, 100)))
                .contains(trip.getId());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<InitialAttachmentUploadBatchSnapshot> upload = pool.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            InitialAttachmentUploadBatchSnapshot batch = transactions.prepareBatch(
                                    trip.getId(), owner.getUserId(), uploadRequest());
                            uploaded.countDown();
                            awaitLatch(commitUpload);
                            return batch;
                        }));
                awaitLatch(uploaded);
                Future<?> expiration = pool.submit(() -> {
                    expirationStarted.countDown();
                    transactions.failUnstartedTrip(trip.getId());
                });
                awaitLatch(expirationStarted);
                assertThatThrownBy(() -> expiration.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                commitUpload.countDown();
                InitialAttachmentUploadBatchSnapshot batch = upload.get(10, TimeUnit.SECONDS);
                expiration.get(10, TimeUnit.SECONDS);

                assertThat(executions.findByTripId(trip.getId()).orElseThrow().getExecutionId())
                        .isEqualTo(batch.executionId());
                assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageReservedBytes())
                        .isEqualTo(461L);
                assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus())
                        .isEqualTo(ProcessingStatus.PROCESSING);
            } finally {
                commitUpload.countDown();
            }
        }
    }

    @Test
    void 만료가_먼저_잠그면_뒤늦은_첫_업로드를_거절한다() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        CountDownLatch commitExpiration = new CountDownLatch(1);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> expiration = pool.submit(() ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            transactions.failUnstartedTrip(trip.getId());
                            expired.countDown();
                            awaitLatch(commitExpiration);
                        }));
                awaitLatch(expired);
                Future<?> upload = pool.submit(() -> {
                    uploadStarted.countDown();
                    transactions.prepareBatch(trip.getId(), owner.getUserId(), uploadRequest());
                });
                awaitLatch(uploadStarted);
                assertThatThrownBy(() -> upload.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                commitExpiration.countDown();
                expiration.get(10, TimeUnit.SECONDS);

                assertThatThrownBy(() -> upload.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
                assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus())
                        .isEqualTo(ProcessingStatus.FAILED);
                assertThat(executions.findByTripId(trip.getId())).isEmpty();
                assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageReservedBytes())
                        .isEqualTo(456L);
            } finally {
                commitExpiration.countDown();
            }
        }
    }

    @Test
    void 두_워커가_동시에_만료해도_용량과_실행_기록을_변경하지_않는다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> {
                awaitLatch(start);
                transactions.failUnstartedTrip(trip.getId());
            });
            Future<?> second = pool.submit(() -> {
                awaitLatch(start);
                transactions.failUnstartedTrip(trip.getId());
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isEqualTo(456L);
        assertThat(executions.findByTripId(trip.getId())).isEmpty();
    }

    @Test
    void 백건을_넘는_미시작_여행은_다음_실행에서_계속_정리한다() {
        List<Trip> backlog = IntStream.range(0, 100)
                .mapToObj(index -> new Trip(owner.getUserId(), "대기" + index, LocalDate.now(), LocalDate.now()))
                .toList();
        trips.saveAll(backlog);
        jdbc.update("UPDATE trips SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 11 MINUTE WHERE user_id = ?",
                owner.getUserId());

        scheduler.failExpiredUploads();

        assertThat(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(owner.getUserId(), ProcessingStatus.FAILED))
                .isEqualTo(100L);
        assertThat(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(owner.getUserId(), ProcessingStatus.PROCESSING))
                .isEqualTo(1L);
        scheduler.failExpiredUploads();
        assertThat(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(owner.getUserId(), ProcessingStatus.FAILED))
                .isEqualTo(101L);
    }

    private void assertProtected() {
        assertThat(trips.findUnstartedUploadExpired(transactions.databaseTime().minusMinutes(10), PageRequest.of(0, 100)))
                .doesNotContain(trip.getId());
        transactions.failUnstartedTrip(trip.getId());
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageUsedBytes()).isEqualTo(123L);
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isEqualTo(456L);
    }

    private void saveRelatedData(RelatedData relatedData) {
        LocalDateTime now = transactions.databaseTime();
        String id = UUID.randomUUID().toString();
        switch (relatedData) {
            case CREATION, ENDED_CREATION -> {
                TripCreationExecution execution = new TripCreationExecution(id, trip.getId(), owner.getUserId(), 1);
                if (relatedData == RelatedData.ENDED_CREATION) {
                    execution.finish(TripCreationExecution.Status.FAILED, ErrorMessage.UPLOAD_TIMEOUT.name(), now);
                }
                executions.save(execution);
            }
            case ADDITION, ENDED_ADDITION -> {
                AdditionalAttachmentExecution execution = new AdditionalAttachmentExecution(
                        id, trip.getId(), owner.getUserId(), 1, now);
                if (relatedData == RelatedData.ENDED_ADDITION) {
                    execution.finish(AdditionalAttachmentExecution.Status.FAILED, ErrorMessage.UPLOAD_TIMEOUT.name(), now);
                }
                additions.save(execution);
            }
            case INITIAL_BATCH -> initialBatches.save(new InitialAttachmentUploadBatch(
                    id, id, trip.getId(), owner.getUserId(), 1, 1, true));
            case ADDITIONAL_BATCH -> additionalBatches.save(new AdditionalAttachmentUploadBatch(
                    id, id, trip.getId(), owner.getUserId(), 1, 1, true));
            case PHOTO, DELETED_PHOTO -> {
                StoredFile file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg", id, "image/jpeg"));
                TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + id, "preview/" + id);
                if (relatedData == RelatedData.DELETED_PHOTO) {
                    attachment.softDelete(now);
                }
                attachments.save(attachment);
                assertThat(files.findById(file.getId()).orElseThrow().getDeletedAt()).isNull();
            }
        }
    }

    private InitialAttachmentUploadUrlRequest uploadRequest() {
        return new InitialAttachmentUploadUrlRequest(1, 1, true, List.of(
                new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 5L)));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private enum RelatedData {
        CREATION,
        ENDED_CREATION,
        ADDITION,
        ENDED_ADDITION,
        INITIAL_BATCH,
        ADDITIONAL_BATCH,
        PHOTO,
        DELETED_PHOTO
    }
}
