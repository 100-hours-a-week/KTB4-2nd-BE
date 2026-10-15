package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class StoryGenerationOutboxPersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationOutboxRepository outboxes;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private StoryGenerationJob job;

    @BeforeEach
    void prepareJob() {
        User owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "여행",
                LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        job = jobs.saveAndFlush(new StoryGenerationJob(UUID.randomUUID().toString(),
                trip.getId(), owner.getUserId(), Story.Mood.PLAIN));
    }

    @Test
    void persistsLargeJsonAndInitialPublicationStateWithoutLeaseColumns() {
        String payload = "{\"data\":\"" + "a".repeat(70000) + "\"}";
        var outbox = new StoryGenerationOutbox(job.getId(), payload, "request-original");
        outboxes.saveAndFlush(outbox);
        entityManager.clear();

        var found = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(found.getId()).isPositive();
        assertThat(found.getGenerationId()).isEqualTo(job.getId());
        assertThat(found.getPayload()).isEqualTo(payload);
        assertThat(found.getRequestId()).isEqualTo("request-original");
        assertThat(found.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PENDING);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '15' AND success = 1
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'story_generation_outbox'
                  AND column_name IN ('lease_token', 'lease_expires_at')
                """, Integer.class)).isZero();
    }

    @Test
    void persistsPublicationStartAndSuccessWithoutChangingPayload() {
        var outbox = outboxes.saveAndFlush(new StoryGenerationOutbox(job.getId(), "{}", "request-1"));
        outbox.beginPublishing();
        entityManager.flush();
        entityManager.clear();

        var publishing = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(publishing.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHING);
        assertThat(publishing.getAttemptCount()).isEqualTo(1);
        assertThat(publishing.getPublishedAt()).isNull();
        LocalDateTime time = LocalDateTime.of(2026, 10, 11, 17, 0);
        publishing.publicationSucceeded(time);
        publishing.publicationSucceeded(time.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        var published = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(time);
        assertThat(published.getLastError()).isNull();
        assertThat(published.getAttemptCount()).isEqualTo(1);
        assertThat(published.getPayload()).isEqualTo("{}");
        assertThat(published.getRequestId()).isEqualTo("request-1");
    }

    @Test
    void persistsFailureReasonAndLeavesMessageUnpublished() {
        var outbox = outboxes.saveAndFlush(new StoryGenerationOutbox(job.getId(), "{}", "request-1"));
        outbox.beginPublishing();
        outbox.publicationFailed("broker unavailable");
        entityManager.flush();
        entityManager.clear();

        var found = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PENDING);
        assertThat(found.getLastError()).isEqualTo("broker unavailable");
        assertThat(found.getAttemptCount()).isEqualTo(1);
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getPayload()).isEqualTo("{}");
        assertThat(found.getRequestId()).isEqualTo("request-1");
    }

    @Test
    void databaseRejectsSecondMessageForSameGeneration() {
        outboxes.saveAndFlush(new StoryGenerationOutbox(job.getId(), "{}", "request-1"));

        assertThatThrownBy(() -> insert(job.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_story_outbox_generation");
    }

    @Test
    void databaseRejectsMessageWithoutExistingGeneration() {
        assertThatThrownBy(() -> insert(-1L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_story_outbox_generation");
    }

    private void insert(Long generationId) {
        jdbcTemplate.update("""
                INSERT INTO story_generation_outbox (generation_id, payload, request_id)
                VALUES (?, ?, ?)
                """, generationId, "{}", "request-other");
    }
}
