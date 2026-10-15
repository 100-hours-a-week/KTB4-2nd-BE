package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentUploadOutboxPersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private AdditionalAttachmentUploadBatchRepository batches;
    @Autowired private AdditionalAttachmentUploadOutboxRepository outboxes;
    @Autowired private EntityManager entityManager;

    private Long batchId;

    @BeforeEach
    void prepareBatch() {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "추가회원"));
        var trip = trips.saveAndFlush(new Trip(user.getUserId(), "추가여행",
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)));
        batchId = batches.saveAndFlush(new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                trip.getId(), user.getUserId(), 1, 1, true)).getId();
    }

    @Test
    void reloadsLargePayloadAndFindsOnlyMatchingBatchAndType() {
        String payload = "{\"data\":\"" + "a".repeat(70_000) + "\"}";
        var ready = outboxes.saveAndFlush(new AdditionalAttachmentUploadOutbox(
                batchId, "photos_ready", payload, "request-original"));
        var process = outboxes.saveAndFlush(new AdditionalAttachmentUploadOutbox(
                batchId, "process", "{}", "request-original"));
        entityManager.clear();

        var found = outboxes.findByUploadBatchIdAndMessageType(batchId, "photos_ready").orElseThrow();
        assertThat(found.getId()).isEqualTo(ready.getId());
        assertThat(found.getUploadBatchId()).isEqualTo(batchId);
        assertThat(found.getMessageType()).isEqualTo("photos_ready");
        assertThat(found.getPayload()).isEqualTo(payload);
        assertThat(found.getRequestId()).isEqualTo("request-original");
        assertThat(found.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PENDING);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(outboxes.findByUploadBatchIdAndMessageType(batchId, "process")).get()
                .extracting(AdditionalAttachmentUploadOutbox::getId).isEqualTo(process.getId());
        assertThat(outboxes.findByUploadBatchIdAndMessageType(Long.MAX_VALUE, "photos_ready")).isEmpty();
        assertThat(outboxes.findByUploadBatchIdAndMessageType(batchId, "unknown")).isEmpty();
    }

    @Test
    void persistsPublishingThenPublishedWithoutChangingMessage() {
        var outbox = outboxes.saveAndFlush(new AdditionalAttachmentUploadOutbox(
                batchId, "photos_ready", "{}", "request-1"));
        outbox.beginPublishing();
        entityManager.flush();
        entityManager.clear();

        var publishing = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(publishing.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHING);
        assertThat(publishing.getAttemptCount()).isEqualTo(1);
        assertThat(publishing.getPublishedAt()).isNull();
        LocalDateTime time = LocalDateTime.of(2026, 10, 13, 10, 0);
        publishing.publicationSucceeded(time);
        publishing.publicationSucceeded(time.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        var published = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(time);
        assertThat(published.getAttemptCount()).isEqualTo(1);
        assertThat(published.getLastError()).isNull();
        assertThat(published.getPayload()).isEqualTo("{}");
        assertThat(published.getRequestId()).isEqualTo("request-1");
    }

    @Test
    void persistsFailureAndClearsErrorOnNextPublicationAttempt() {
        var outbox = outboxes.saveAndFlush(new AdditionalAttachmentUploadOutbox(
                batchId, "photos_ready", "{}", "request-1"));
        outbox.beginPublishing();
        outbox.publicationFailed("broker unavailable");
        entityManager.flush();
        entityManager.clear();

        var failed = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PENDING);
        assertThat(failed.getLastError()).isEqualTo("broker unavailable");
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getPublishedAt()).isNull();
        failed.beginPublishing();
        entityManager.flush();
        entityManager.clear();

        var retrying = outboxes.findById(outbox.getId()).orElseThrow();
        assertThat(retrying.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHING);
        assertThat(retrying.getAttemptCount()).isEqualTo(2);
        assertThat(retrying.getLastError()).isNull();
        assertThat(retrying.getPayload()).isEqualTo("{}");
        assertThat(retrying.getRequestId()).isEqualTo("request-1");
    }
}
