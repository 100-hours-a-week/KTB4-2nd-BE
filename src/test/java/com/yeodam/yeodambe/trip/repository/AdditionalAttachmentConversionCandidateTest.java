package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class AdditionalAttachmentConversionCandidateTest {
    @Autowired private EntityManager entityManager;
    @Autowired private AdditionalAttachmentUploadBatchRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);
    private User user;
    private Trip trip;
    private int batchNo;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET next_attempt_at = ?",
                NOW.plusYears(100));
        user = new User(UUID.randomUUID() + "@yeodam.test", "candidate");
        entityManager.persist(user);
        trip = new Trip(user.getUserId(), "candidate", LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1));
        entityManager.persist(trip);
    }

    @Test
    void selectsVerifiedAndExpiredConvertingButExcludesLiveLeaseFutureRetryAndEndedExecution() {
        var verified = batch();
        verified.markVerified();
        var expired = batch();
        expired.markVerified();
        expired.claimConversion("expired", NOW);
        var retryDue = batch();
        retryDue.markVerified();
        retryDue.claimConversion("retry", NOW.minusMinutes(1));
        retryDue.retryConversion("retry", NOW);
        batch();
        var prepared = batch();
        prepared.markVerified();
        prepared.markPrepared();
        var live = batch();
        live.markVerified();
        live.claimConversion("live", NOW.plusSeconds(1));
        var future = batch();
        future.markVerified();
        future.claimConversion("future", NOW.minusMinutes(1));
        future.retryConversion("future", NOW.plusSeconds(1));
        var ended = batch();
        ended.markVerified();
        entityManager.find(AdditionalAttachmentExecution.class, ended.getAdditionId())
                .finish(AdditionalAttachmentExecution.Status.FAILED, "FAILED", NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findPendingConversion(NOW, PageRequest.of(0, 20)))
                .extracting(AdditionalAttachmentUploadBatch::getId)
                .containsExactly(verified.getId(), expired.getId(), retryDue.getId());
    }

    @Test
    void limitsCandidatesAndOrdersByBatchId() {
        var first = batch();
        first.markVerified();
        var second = batch();
        second.markVerified();
        var third = batch();
        third.markVerified();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findPendingConversion(NOW, PageRequest.of(0, 2)))
                .extracting(AdditionalAttachmentUploadBatch::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(repository.findPendingConversion(NOW, PageRequest.of(1, 2)))
                .extracting(AdditionalAttachmentUploadBatch::getId)
                .containsExactly(third.getId());
    }

    @Test
    void excludesMissingExecutionAndMismatchedTripOrOwner() {
        var missing = persistBatch(UUID.randomUUID().toString(), trip.getId(), user.getUserId());
        missing.markVerified();
        var other = new User(UUID.randomUUID() + "@yeodam.test", "other");
        entityManager.persist(other);
        var otherTrip = new Trip(other.getUserId(), "other", LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1));
        entityManager.persist(otherTrip);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), trip.getId(), user.getUserId(), 1, NOW);
        entityManager.persist(execution);
        persistBatch(execution.getExecutionId(), otherTrip.getId(), user.getUserId()).markVerified();
        persistBatch(execution.getExecutionId(), trip.getId(), other.getUserId()).markVerified();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findPendingConversion(NOW, PageRequest.of(0, 20))).isEmpty();
    }

    private AdditionalAttachmentUploadBatch batch() {
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), trip.getId(), user.getUserId(), 1, NOW);
        entityManager.persist(execution);
        return persistBatch(execution.getExecutionId(), trip.getId(), user.getUserId());
    }

    private AdditionalAttachmentUploadBatch persistBatch(String executionId, Long tripId, Long userId) {
        var batch = new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), executionId, tripId, userId,
                ++batchNo, 1, true);
        entityManager.persist(batch);
        return batch;
    }
}
