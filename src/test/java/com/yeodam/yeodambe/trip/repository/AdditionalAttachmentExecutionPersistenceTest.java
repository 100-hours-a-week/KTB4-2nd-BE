package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
class AdditionalAttachmentExecutionPersistenceTest {
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;

    @Test
    void reloadsExecutionIdentityInitialStateAndDatabaseTimestamps() {
        Long userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        Long tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
        String executionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        entityManager.persist(new AdditionalAttachmentExecution(executionId, tripId, userId, 20, now));
        entityManager.flush();
        entityManager.clear();

        AdditionalAttachmentExecution loaded = entityManager.find(AdditionalAttachmentExecution.class, executionId);
        assertThat(loaded.getExecutionId()).isEqualTo(executionId);
        assertThat(loaded.getTripId()).isEqualTo(tripId);
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.UPLOADING);
        assertThat(loaded.getTotalAttachmentCount()).isEqualTo(20);
        assertThat(loaded.getAnalysisAttachmentCount()).isNull();
        assertThat(loaded.isInputClosed()).isFalse();
        assertThat(loaded.getLastUploadActivityAt()).isEqualTo(now);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void persistsAcceptedUploadActivityReservationAndInputClosure() {
        Long userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        Long tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
        String executionId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 13, 14, 0);
        LocalDateTime activityAt = startedAt.plusMinutes(2);
        var execution = new AdditionalAttachmentExecution(executionId, tripId, userId, 20, startedAt);
        entityManager.persist(execution);
        execution.reserve(1024L);
        execution.reserve(2048L);
        execution.recordUploadActivity(activityAt);
        execution.closeInput();
        entityManager.flush();
        entityManager.clear();

        var loaded = entityManager.find(AdditionalAttachmentExecution.class, executionId);
        assertThat(loaded.getReservedBytes()).isEqualTo(3072L);
        assertThat(loaded.getAccountedBytes()).isZero();
        assertThat(loaded.isReservationReleased()).isFalse();
        assertThat(loaded.getLastUploadActivityAt()).isEqualTo(activityAt);
        assertThat(loaded.isInputClosed()).isTrue();
        assertThat(loaded.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.IMAGE_PROCESSING);
        assertThat(loaded.isTerminal()).isFalse();
        assertThat(loaded.getProcessPublishedAt()).isNull();
        assertThat(loaded.getResultRawPayload()).isNull();
    }

    @Test
    void reloadsReceivedResultAndRetryHistoryAfterReleasingClaim() {
        Long userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        Long tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
        String executionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        String payload = "{\"result\":\"" + "x".repeat(70_000) + "\"}";
        var execution = new AdditionalAttachmentExecution(executionId, tripId, userId, 20, now);
        entityManager.persist(execution);
        execution.receiveResult(payload, "COMPLETED", now);
        execution.recordConflictingResult("{\"different\":true}");
        execution.claimResult("worker", now.plusMinutes(5));
        execution.startResultAttempt(now);
        execution.renewResultLease(now.plusMinutes(6));
        execution.recordResultFailure("database-unavailable", now.plusSeconds(1));
        execution.retryResult(now.plusSeconds(10));
        entityManager.flush();
        entityManager.clear();

        var loaded = entityManager.find(AdditionalAttachmentExecution.class, executionId);
        assertThat(loaded.getResultRawPayload()).isEqualTo(payload);
        assertThat(loaded.getResultAiStatus()).isEqualTo("COMPLETED");
        assertThat(loaded.getResultReceivedAt()).isEqualTo(now);
        assertThat(loaded.getResultConflicts()).isEqualTo("{\"different\":true}");
        assertThat(loaded.getResultAttempts()).isEqualTo(1);
        assertThat(loaded.getResultAttemptStartedAt()).isEqualTo(now);
        assertThat(loaded.getResultFailureReason()).isEqualTo("database-unavailable");
        assertThat(loaded.getResultFailedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(loaded.getResultNextAttemptAt()).isEqualTo(now.plusSeconds(10));
        assertThat(loaded.getResultClaimToken()).isNull();
        assertThat(loaded.getResultLeaseUntil()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);
        assertThat(loaded.isTerminal()).isFalse();
    }

    @Test
    void persistsFailedSettlementAndFirstEndTimeWithoutAResultClaim() {
        Long userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        Long tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
        String executionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(executionId, tripId, userId, 20, now);
        entityManager.persist(execution);
        execution.reserve(3072L);
        execution.receiveResult("{}", "COMPLETED", now);
        execution.claimResult("worker", now.plusMinutes(5));
        execution.releaseReservation(0L);
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, "AI_RESULT_APPLICATION_FAILED", now);
        execution.releaseReservation(3072L);
        execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, now.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        var loaded = entityManager.find(AdditionalAttachmentExecution.class, executionId);
        assertThat(loaded.isReservationReleased()).isTrue();
        assertThat(loaded.getReservedBytes()).isZero();
        assertThat(loaded.getAccountedBytes()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(loaded.getErrorCode()).isEqualTo("AI_RESULT_APPLICATION_FAILED");
        assertThat(loaded.getEndedAt()).isEqualTo(now);
        assertThat(loaded.getResultClaimToken()).isNull();
        assertThat(loaded.getResultLeaseUntil()).isNull();
    }
}
