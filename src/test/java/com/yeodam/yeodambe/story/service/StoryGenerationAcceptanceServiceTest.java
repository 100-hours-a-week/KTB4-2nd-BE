package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryGenerationAcceptanceServiceTest {
    @Autowired private StoryGenerationAcceptanceService service;
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
        folder = folders.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
        file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
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
        jdbcTemplate.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        files.deleteById(file.getId());
        users.deleteById(owner.getUserId());
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
    void publicationStateServiceCommitsStartAndSuccessBetweenCalls() {
        photo(takenAt);
        var job = service.accept(owner.getUserId(), trip.getId(), request(), "request-original");

        var started = publicationState.begin(job.getId());
        var publishing = outboxes.findById(started.getId()).orElseThrow();
        assertThat(publishing.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHING);
        assertThat(publishing.getAttemptCount()).isEqualTo(1);
        LocalDateTime publishedAt = LocalDateTime.of(2026, 10, 11, 18, 0);

        publicationState.succeeded(started.getId(), publishedAt);

        var published = outboxes.findById(started.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(published.getPayload()).isEqualTo(started.getPayload());
        assertThat(published.getRequestId()).isEqualTo("request-original");
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void publicationStateServiceCommitsFailureWithoutPublishingMessage() {
        photo(takenAt);
        var job = service.accept(owner.getUserId(), trip.getId(), request(), "request-original");
        var started = publicationState.begin(job.getId());

        publicationState.failed(started.getId(), "broker unavailable");

        var failed = outboxes.findById(started.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("broker unavailable");
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getPayload()).isEqualTo(started.getPayload());
        var failedJob = jobs.findById(job.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(failedJob.getErrorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_trip_id FROM story_generation_jobs WHERE generation_id = ?",
                Long.class, job.getId())).isNull();
    }

    @Test
    void assemblyRejectionRollsBackPreviouslyFlushedJobAndSelection() {
        photo(null);

        assertThatThrownBy(() -> service.accept(owner.getUserId(), trip.getId(), request(), "request-original"))
                .isInstanceOf(InvalidStoryRequestException.class);

        assertNoSavedGeneration();
        assertThat(attachments.countActiveByTripId(trip.getId())).isEqualTo(1);
    }

    @Test
    void outboxConstraintFailureRollsBackJobAndSelection() {
        photo(takenAt);

        assertThatThrownBy(() -> service.accept(owner.getUserId(), trip.getId(), request(), null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNoSavedGeneration();
        assertThat(attachments.countActiveByTripId(trip.getId())).isEqualTo(1);
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
        return attachments.saveAndFlush(photo);
    }
}
