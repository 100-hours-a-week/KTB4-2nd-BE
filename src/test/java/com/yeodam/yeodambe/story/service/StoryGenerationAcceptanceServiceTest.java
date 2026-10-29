package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotAllowedException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusNotification;
import static org.mockito.Mockito.*;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryGenerationAcceptanceServiceTest {
    @Autowired private StoryGenerationAcceptanceService service;
    @Autowired private AdditionalAttachmentUploadUrlService additionalUpload;
    @Autowired private UserStatsRepository stats;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private StoryGenerationPublicationStateService publicationState;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationPlaceRepository selections;
    @Autowired private StoryGenerationOutboxRepository outboxes;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebSocketEventPublisher publisher;

    private User owner;
    private Trip trip;
    private TripDetailPlace folder;
    private StoredFile file;
    private final LocalDateTime takenAt = LocalDateTime.of(2026, 10, 11, 10, 0);

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trips.save(new Trip(owner.getUserId(), "여행",
                LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        folder = folders.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
        file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
        file.storageSize(1024L);
        files.saveAndFlush(file);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM story_generation_outbox WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbcTemplate.update("DELETE FROM story_generation_places WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbcTemplate.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM trip_attachments WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM additional_attachment_upload_items WHERE upload_batch_id IN "
                + "(SELECT upload_batch_id FROM additional_attachment_upload_batches WHERE trip_id = ?)", trip.getId());
        jdbcTemplate.update("DELETE FROM additional_attachment_upload_batches WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM additional_attachment_executions WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM user_stats WHERE user_id = ?", owner.getUserId());
        files.deleteById(file.getId());
        users.deleteById(owner.getUserId());
    }

    @Test
    void queuedNotificationIsPublishedOnlyAfterCommit() {
        photo(takenAt);
        var job = new TransactionTemplate(transactionManager).execute(transaction -> {
            var accepted = service.accept(owner.getUserId(), trip.getId(), request(), "queued-test");
            verifyNoInteractions(publisher);
            return accepted;
        });
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var event = captor.getValue();
        assertThat(event.target()).isEqualTo(new WebSocketEventTarget(WebSocketEventTargetType.USER, owner.getUserId()));
        assertThat(event.payload().message()).isEqualTo("STORY_GENERATION_STATUS_FOUND");
        var notification = (StoryGenerationStatusNotification) event.payload().data();
        assertThat(notification.generationId()).isEqualTo(job.getId());
        assertThat(notification.tripId()).isEqualTo(trip.getId());
        assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(notification.statusVersion()).isEqualTo(1);
        assertThat(notification.storyId()).isNull();
        assertThat(notification.error()).isNull();
    }

    @Test
    void rolledBackAcceptanceDoesNotPublishQueuedNotification() {
        photo(takenAt);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.accept(owner.getUserId(), trip.getId(), request(), "request-original");
            transaction.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        assertNoSavedGeneration();
    }

    @Test
    void commitsJobSelectionAndSerializedMessageTogether() {
        TripAttachment photo = photo(takenAt);

        StoryGenerationJob result = service.accept(owner.getUserId(), trip.getId(), request(), "request-original");

        var savedJob = jobs.findById(result.getId()).orElseThrow();
        assertThat(savedJob.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(selections.findAllByGenerationIdOrderByOrderNumberAsc(result.getId()))
                .singleElement().satisfies(selected -> assertThat(selected.getTripPlaceId()).isEqualTo(folder.getId()));
        Long outboxId = jdbcTemplate.queryForObject(
                "SELECT outbox_id FROM story_generation_outbox WHERE generation_id = ?",
                Long.class, result.getId());
        var outbox = outboxes.findById(outboxId).orElseThrow();
        assertThat(outbox.isCdcManaged()).isTrue();
        assertThat(outbox.getTripId()).isEqualTo(trip.getId());
        assertThat(outbox.getRequestId()).isEqualTo("request-original");
        assertThat(outbox.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PENDING);
        var payload = objectMapper.readTree(outbox.getPayload());
        assertThat(payload.path("execution_id").asString()).isEqualTo(savedJob.getExecutionId());
        assertThat(payload.path("trip_id").asLong()).isEqualTo(trip.getId());
        assertThat(payload.path("mood").asString()).isEqualTo("EMOTIONAL");
        var sentPhoto = payload.path("places").get(0).path("attachments").get(0);
        assertThat(sentPhoto.path("trip_attachment_id").asLong()).isEqualTo(photo.getId());
        assertThat(sentPhoto.path("analyze_storage_key").asString()).isEqualTo("analysis/photo.jpg");
        assertThat(sentPhoto.path("taken_at").asString()).isEqualTo("2026-10-11T10:00:00+09:00");
    }

    @Test
    void publicationReceiptMarksExistingOutboxWithoutChangingQueuedJob() {
        photo(takenAt);
        var job = service.accept(owner.getUserId(), trip.getId(), request(), "request-original");
        var outbox = outboxes.findByGenerationId(job.getId()).orElseThrow();
        var appended = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        publicationState.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), outbox.getTripId().toString(), outbox.getPayload(), appended));
        var published = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHED);
        assertThat(jdbcTemplate.queryForObject("SELECT UNIX_TIMESTAMP(published_at) * 1000 FROM story_generation_outbox WHERE outbox_id = ?",
                java.math.BigDecimal.class, outbox.getId()).longValueExact()).isEqualTo(appended.toEpochMilli());
        publicationState.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), trip.getId().toString(), outbox.getPayload(), appended.plusSeconds(60)));
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getPublishedAt())
                .isEqualTo(published.getPublishedAt());
        assertThatThrownBy(() -> publicationState.recordPublished(outbox.getId(),
                new OutboxReceipt(
                        outbox.getTopic(), trip.getId().toString(), "mismatched-payload", appended)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(published.getPayload()).isEqualTo(outbox.getPayload());
        assertThat(published.getRequestId()).isEqualTo("request-original");
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void lateReceiptPreservesCanceledJob() {
        photo(takenAt);
        var job = service.accept(owner.getUserId(), trip.getId(), request(), "request-canceled");
        job.cancel();
        jobs.saveAndFlush(job);
        var outbox = outboxes.findByGenerationId(job.getId()).orElseThrow();

        publicationState.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), trip.getId().toString(), outbox.getPayload(), Instant.now()));

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void pendingPublicationPreservesJobAndPayload() {
        photo(takenAt);
        var job = service.accept(owner.getUserId(), trip.getId(), request(), "request-original");
        var outbox = outboxes.findByGenerationId(job.getId()).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PENDING);
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getPayload()).isNotBlank();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void assemblyRejectionRollsBackPreviouslyFlushedJobAndSelection() {
        photo(null);

        assertThatThrownBy(() -> service.accept(owner.getUserId(), trip.getId(), request(), "request-original"))
                .isInstanceOf(InvalidStoryRequestException.class);

        assertNoSavedGeneration();
        verifyNoInteractions(publisher);
        assertThat(attachments.countActiveByTripId(trip.getId())).isEqualTo(1);
    }

    @Test
    void outboxConstraintFailureRollsBackJobAndSelection() {
        photo(takenAt);

        assertThatThrownBy(() -> service.accept(owner.getUserId(), trip.getId(), request(), null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNoSavedGeneration();
        verifyNoInteractions(publisher);
        assertThat(attachments.countActiveByTripId(trip.getId())).isEqualTo(1);
    }

    @Test
    void rejectsProcessingTripWithoutSavingJobSelectionOrOutbox() {
        jdbcTemplate.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", trip.getId());
        photo(takenAt);

        assertThatThrownBy(() -> service.accept(owner.getUserId(), trip.getId(), request(), "request-processing"))
                .isInstanceOf(StoryGenerationNotAllowedException.class);

        assertNoSavedGeneration();
        verifyNoInteractions(publisher);
        assertThat(attachments.countActiveByTripId(trip.getId())).isEqualTo(1);
    }

    @Test
    void storyAcceptanceBlocksConcurrentPhotoAdditionUntilCommitThenRejectsIt() throws Exception {
        photo(takenAt);
        var addition = additionRequest();
        assertSecondRequestRejectedAfterCommit(
                () -> service.accept(owner.getUserId(), trip.getId(), request(), "request-original"),
                () -> additionalUpload.prepareBatch(trip.getId(), owner.getUserId(), addition),
                TripAttachmentAddNotAllowedException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM story_generation_jobs WHERE trip_id = ?",
                Integer.class, trip.getId())).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id = ?",
                Integer.class, trip.getId())).isZero();
    }

    @Test
    void photoAdditionBlocksConcurrentStoryAcceptanceUntilCommitThenRejectsIt() throws Exception {
        photo(takenAt);
        stats.saveAndFlush(new UserStats(owner));
        var addition = additionRequest();
        assertSecondRequestRejectedAfterCommit(
                () -> additionalUpload.prepareBatch(trip.getId(), owner.getUserId(), addition),
                () -> service.accept(owner.getUserId(), trip.getId(), request(), "request-original"),
                StoryGenerationNotAllowedException.class);
        assertNoSavedGeneration();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id = ?",
                Integer.class, trip.getId())).isOne();
    }

    private AdditionalAttachmentUploadUrlRequest additionRequest() {
        return new AdditionalAttachmentUploadUrlRequest(UUID.randomUUID().toString(), 1, 1, true,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("new.jpg", "image/jpeg", 1024L)));
    }

    private void assertSecondRequestRejectedAfterCommit(
            Runnable first, Runnable second, Class<? extends Throwable> expected
    ) throws Exception {
        var firstSaved = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                first.run();
                firstSaved.countDown();
                try {
                    if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("commit signal timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            try {
                if (!firstSaved.await(10, TimeUnit.SECONDS)) {
                    firstFuture.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("first request did not reach commit barrier");
                }
                var secondFuture = executor.submit(() -> {
                    secondStarted.countDown();
                    second.run();
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> secondFuture.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                allowCommit.countDown();
                firstFuture.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> secondFuture.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(expected);
            } finally {
                allowCommit.countDown();
            }
        }
    }

    private void assertNoSavedGeneration() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM story_generation_jobs WHERE trip_id = ?",
                Integer.class, trip.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM story_generation_places WHERE trip_place_id = ?",
                Integer.class, folder.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM story_generation_outbox WHERE request_id = ?",
                Integer.class, "request-original")).isZero();
    }

    private StoryGenerationRequest request() {
        return new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(folder.getId()));
    }

    private TripAttachment photo(LocalDateTime time) {
        var photo = TripAttachment.initial(trip.getId(), file.getId(), "analysis/photo.jpg", "preview/photo.jpg");
        photo.classify(folder.getId(), RegionOrigin.EXIF, time, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        photo.storageSizes(100L, 50L, null);
        return attachments.saveAndFlush(photo);
    }
}
