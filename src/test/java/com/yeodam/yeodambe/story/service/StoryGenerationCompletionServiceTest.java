package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryGenerationCompletionServiceTest {
    @Autowired private StoryGenerationCompletionService service;
    @Autowired private StoryGenerationAcceptanceService acceptance;
    @Autowired private StoryGenerationTerminalService terminal;
    @Autowired private StoryGenerationPublicationStateService publicationState;
    @Autowired private StoryGenerationTimeoutService timeout;
    @Autowired private StoryGenerationProgressService progress;
    @Autowired private StoryGenerationStatusService status;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbc;

    private User owner;
    private Trip trip;
    private TripDetailPlace folder;
    private StoredFile file;
    private TripAttachment photo;
    private StoryGenerationJob job;

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trips.save(new Trip(owner.getUserId(), "여행",
                LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        var takenAt = LocalDateTime.of(2026, 10, 11, 10, 0);
        folder = folders.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
        file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
        var attachment = TripAttachment.initial(trip.getId(), file.getId(), "analysis/photo.jpg", "preview/photo.jpg");
        attachment.classify(folder.getId(), RegionOrigin.EXIF, takenAt, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        photo = attachments.saveAndFlush(attachment);
        job = acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(folder.getId())), "completion-test");
    }

    @AfterEach
    void cleanUp() {
        if (trip == null) {
            return;
        }
        jdbc.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM story_generation_outbox WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_places WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM story_blocks WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_selected_places WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM stories WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_attachments WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        if (file != null) {
            files.deleteById(file.getId());
        }
        users.deleteById(owner.getUserId());
    }

    @Test
    void findsSavedGenerationIdByExecutionIdAndReturnsEmptyForUnknownExecution() {
        var found = jobs.findByExecutionId(job.getExecutionId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(job.getId());
        assertThat(found.getTripId()).isEqualTo(trip.getId());
        assertThat(jobs.findByExecutionId("unknown-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void commitsStoryBlocksSelectionTripLinkAndFinishedJobTogether() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertCompletedOnce();
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        assertThat(jdbc.queryForObject("SELECT processing_status FROM stories WHERE story_id = ?",
                String.class, storyId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT mood FROM stories WHERE story_id = ?", String.class, storyId))
                .isEqualTo("EMOTIONAL");
        assertThat(jdbc.queryForObject("SELECT trip_attachment_id FROM story_blocks WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(photo.getId());
        assertThat(jdbc.queryForObject("SELECT trip_place_id FROM story_selected_places WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(folder.getId());
        assertThat(jdbc.queryForObject("SELECT source_trip_place_id FROM story_blocks WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(folder.getId());
        assertThat(jdbc.queryForObject("SELECT source_date FROM story_blocks WHERE story_id = ?",
                java.time.LocalDate.class, storyId)).isEqualTo(folder.getStartedAt().toLocalDate());
    }

    @Test
    void repeatedCompletionDoesNotSaveAnotherStoryOrBlock() {
        var response = completed(job.getExecutionId(), photo.getId());
        service.complete(job.getId(), response);
        Long firstStoryId = jobs.findById(job.getId()).orElseThrow().getStoryId();

        service.complete(job.getId(), response);

        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(firstStoryId);
    }

    @Test
    void rejectsOldExecutionBeforeSavingAnyStory() {
        assertThatThrownBy(() -> service.complete(job.getId(), completed("old-execution", photo.getId())))
                .isInstanceOf(IllegalArgumentException.class);

        assertNoResultSaved();
    }

    @Test
    void rejectsPhotoOutsidePublishedRequest() {
        assertThatThrownBy(() -> service.complete(job.getId(), completed(job.getExecutionId(), Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);

        assertNoResultSaved();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"FAILED", "CANCELED"})
    void lateCompletionDoesNotSaveResultForFinishedJob(StoryGenerationJob.Status status) {
        var error = status == StoryGenerationJob.Status.FAILED
                ? new StoryGenerationAiResponse.Failure("WORKER_DEAD", "AI failure", null) : null;
        terminal.apply(job.getId(), new StoryGenerationAiResponse(trip.getId(), job.getExecutionId(),
                StoryGenerationAiResponse.Status.valueOf(status.name()), null, null, null, error));

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertThat(count("stories")).isZero();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode())
                .isEqualTo(status == StoryGenerationJob.Status.FAILED ? "WORKER_DEAD" : null);
    }

    @Test
    void latePublicationFailureDoesNotOverwriteCompletedJob() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        Long outboxId = jdbc.queryForObject("SELECT outbox_id FROM story_generation_outbox WHERE generation_id = ?",
                Long.class, job.getId());

        publicationState.failed(outboxId, "late broker failure");

        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isNull();
    }

    @Test
    void blockForeignKeyFailureRollsBackAlreadyInsertedStoryAndSelection() {
        attachments.deleteById(photo.getId());

        assertThatThrownBy(() -> service.complete(job.getId(), completed(job.getExecutionId(), photo.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNoResultSaved();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_selected_places WHERE trip_place_id = ?",
                Integer.class, folder.getId())).isZero();
    }

    @Test
    void concurrentCompletionCallsCommitOnlyOneResult() throws Exception {
        var response = completed(job.getExecutionId(), photo.getId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> completeAfterStart(ready, start, response));
            var second = executor.submit(() -> completeAfterStart(ready, start, response));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }

        assertCompletedOnce();
    }

    @Test
    void expiredJobIsPersistedAsFailedAndLateResultAndProgressAreIgnored() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = ?
                WHERE generation_id = ?
                """, LocalDateTime.now().minusHours(2), job.getId());
        assertThat(timeout.expire(job.getId(), LocalDateTime.now())).isTrue();

        progress.updateProcessing(job.getId(), new StoryGenerationAiResponse(trip.getId(), job.getExecutionId(),
                StoryGenerationAiResponse.Status.PROCESSING, new StoryGenerationAiResponse.Progress(4, 4),
                null, null, null));
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(count("stories")).isZero();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        var response = status.getStatus(owner.getUserId(), job.getId());
        assertThat(response.status()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(response.error().code()).isEqualTo("STORY_GENERATION_FAILED");
    }

    @Test
    void lateCompletionExpiresJobEvenBeforeNextSchedulerCycle() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = ?
                WHERE generation_id = ?
                """, LocalDateTime.now().minusHours(2), job.getId());
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(count("stories")).isZero();
    }

    @Test
    void completionBeforePublicationConfirmationIgnoresOldCreationDeadline() {
        jdbc.update("""
                UPDATE story_generation_jobs
                SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM story_generation_outbox WHERE generation_id = ?",
                LocalDateTime.class, job.getId())).isNull();

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertCompletedOnce();
    }

    @Test
    void timeoutCheckAfterCompletionKeepsSavedStory() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        assertThat(timeout.expire(job.getId(), LocalDateTime.now().plusHours(2))).isTrue();
        assertCompletedOnce();
    }

    private void completeAfterStart(CountDownLatch ready, CountDownLatch start,
                                    StoryGenerationAiResponse response) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test start timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        service.complete(job.getId(), response);
    }

    private void assertCompletedOnce() {
        assertThat(count("stories")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id IN "
                + "(SELECT story_id FROM stories WHERE trip_id = ?)", Integer.class, trip.getId())).isEqualTo(1);
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(saved.getProgress()).isEqualTo(100);
        assertThat(saved.getStoryId()).isNotNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isEqualTo(saved.getStoryId());
    }

    private void assertNoResultSaved() {
        assertThat(count("stories")).isZero();
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(saved.getStoryId()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE trip_id = ?", Integer.class, trip.getId());
    }

    private StoryGenerationAiResponse completed(String executionId, Long photoId) {
        return new StoryGenerationAiResponse(trip.getId(), executionId, StoryGenerationAiResponse.Status.COMPLETED,
                new StoryGenerationAiResponse.Progress(4, 4), null,
                new StoryGenerationAiResponse.Result("여행 이야기", List.of(
                        new StoryGenerationAiResponse.Block(1, folder.getId(), photoId, "첫날", "사진 설명", "산책했다."))),
                null);
    }
}
