package com.yeodam.yeodambe.trip.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdditionalAttachmentExecutionTest {
    @Test
    void retriesReceivedResultWithNewOwnershipAndPreservesFailureHistory() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        execution.receiveResult("{}", "COMPLETED", now);
        execution.claimResult("first-worker", now.plusMinutes(5));
        execution.startResultAttempt(now);
        execution.recordResultFailure("database-unavailable", now.plusSeconds(1));
        execution.retryResult(now.plusSeconds(10));

        assertThat(execution.getResultClaimToken()).isNull();
        assertThat(execution.getResultLeaseUntil()).isNull();
        assertThat(execution.getResultNextAttemptAt()).isEqualTo(now.plusSeconds(10));
        assertThat(execution.getResultRawPayload()).isEqualTo("{}");
        assertThat(execution.getResultFailureReason()).isEqualTo("database-unavailable");
        assertThat(execution.getResultFailedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);
        assertThat(execution.isTerminal()).isFalse();

        execution.claimResult("second-worker", now.plusMinutes(6));
        execution.startResultAttempt(now.plusSeconds(10));
        assertThat(execution.getResultClaimToken()).isEqualTo("second-worker");
        assertThat(execution.getResultAttempts()).isEqualTo(2);
        assertThat(execution.getResultAttemptStartedAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void renewsLeaseWithoutStartingAnotherAttemptOrChangingOwner() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        execution.receiveResult("{}", "COMPLETED", now);
        execution.claimResult("worker", now.plusMinutes(5));
        assertThat(execution.getResultAttempts()).isZero();
        execution.startResultAttempt(now);
        execution.renewResultLease(now.plusMinutes(6));

        assertThat(execution.getResultClaimToken()).isEqualTo("worker");
        assertThat(execution.getResultLeaseUntil()).isEqualTo(now.plusMinutes(6));
        assertThat(execution.getResultAttempts()).isEqualTo(1);
        assertThat(execution.getResultAttemptStartedAt()).isEqualTo(now);
    }

    @Test
    void preservesOriginalProcessRequestAndAnalysisCountOnRepeatedAttachment() {
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, LocalDateTime.now());
        execution.attachProcessOutbox(120L, 50);
        execution.attachProcessOutbox(121L, 60);

        assertThat(execution.getProcessOutboxId()).isEqualTo(120L);
        assertThat(execution.getAnalysisAttachmentCount()).isEqualTo(50);
        assertThat(execution.getTotalAttachmentCount()).isEqualTo(20);
    }

    @Test
    void recordsFirstPublicationTimeOnlyAndMovesActiveExecutionToQueued() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, first);
        execution.closeInput();
        execution.attachProcessOutbox(120L, 50);
        execution.recordProcessPublished(first);
        execution.recordProcessPublished(first.plusMinutes(5));

        assertThat(execution.getProcessPublishedAt()).isEqualTo(first);
        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.AI_QUEUED);
    }

    @Test
    void latePublicationRecordDoesNotRevertReceivedResultOrTerminalState() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var applying = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        applying.receiveResult("{}", "COMPLETED", now);
        applying.recordProcessPublished(now);
        assertThat(applying.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);

        var failed = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        failed.finish(AdditionalAttachmentExecution.Status.FAILED, "AI_PROCESSING_FAILED", now.minusMinutes(1));
        failed.recordProcessPublished(now);
        assertThat(failed.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(failed.isTerminal()).isTrue();
    }

    @Test
    void preservesFirstResultPayloadStatusAndReceiptTimeOnRepeatedReceipt() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        String original = "{\"result\":{\"places\":[]}}";
        execution.receiveResult(original, "COMPLETED", now);
        execution.receiveResult("{\"error\":\"different-result\"}", "FAILED", now.plusMinutes(1));

        assertThat(execution.getResultRawPayload()).isEqualTo(original);
        assertThat(execution.getResultAiStatus()).isEqualTo("COMPLETED");
        assertThat(execution.getResultReceivedAt()).isEqualTo(now);
        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);
        assertThat(execution.getResultAttempts()).isZero();
        assertThat(execution.getEndedAt()).isNull();
    }

    @Test
    void preservesFirstConflictingPayloadWithoutOverwritingAcceptedResult() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        execution.receiveResult("{\"result\":1}", "COMPLETED", now);
        execution.recordConflictingResult("{\"result\":2}");
        execution.recordConflictingResult("{\"result\":3}");

        assertThat(execution.getResultRawPayload()).isEqualTo("{\"result\":1}");
        assertThat(execution.getResultConflicts()).isEqualTo("{\"result\":2}");
        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.RESULT_APPLYING);
    }

    @Test
    void ignoresLateResultAfterExecutionHasFailed() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, "AI_PROCESSING_FAILED", now);
        execution.receiveResult("{}", "COMPLETED", now.plusMinutes(1));

        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(execution.getEndedAt()).isEqualTo(now);
        assertThat(execution.getResultRawPayload()).isNull();
        assertThat(execution.getResultAiStatus()).isNull();
        assertThat(execution.getResultReceivedAt()).isNull();
    }

    @Test
    void settlesReservationOnceWithoutReplacingOriginalAccountedBytes() {
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, LocalDateTime.now());
        execution.reserve(3072L);
        execution.releaseReservation(3072L);
        execution.releaseReservation(0L);

        assertThat(execution.isReservationReleased()).isTrue();
        assertThat(execution.getReservedBytes()).isZero();
        assertThat(execution.getAccountedBytes()).isEqualTo(3072L);
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentExecution.Status.class,
            names = {"COMPLETED", "FAILED", "CANCELED"})
    void preservesFirstTerminalStateAndReleasesResultClaim(AdditionalAttachmentExecution.Status status) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 20, now);
        execution.receiveResult("{}", "COMPLETED", now);
        execution.claimResult("worker", now.plusMinutes(5));
        String error = status == AdditionalAttachmentExecution.Status.FAILED ? "AI_PROCESSING_FAILED" : null;
        execution.finish(status, error, now);
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, "DIFFERENT_ERROR", now.plusMinutes(1));
        execution.receiveResult("{\"different\":true}", "COMPLETED", now.plusMinutes(1));

        assertThat(execution.getStatus()).isEqualTo(status);
        assertThat(execution.getErrorCode()).isEqualTo(error);
        assertThat(execution.getEndedAt()).isEqualTo(now);
        assertThat(execution.isTerminal()).isTrue();
        assertThat(execution.getResultClaimToken()).isNull();
        assertThat(execution.getResultLeaseUntil()).isNull();
        assertThat(execution.getResultRawPayload()).isEqualTo("{}");
    }

    @Test
    void transfersSavedPhotoBytesFromReservationToAccountedAndRejectsOverSettlement() {
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 2, LocalDateTime.now());
        execution.reserve(3072L);
        execution.accountPhoto(1024L);
        assertThat(execution.getReservedBytes()).isEqualTo(2048L);
        assertThat(execution.getAccountedBytes()).isEqualTo(1024L);
        assertThatThrownBy(() -> execution.accountPhoto(2049L)).isInstanceOf(IllegalStateException.class);
        assertThat(execution.getReservedBytes()).isEqualTo(2048L);
        execution.accountPhoto(2048L);
        execution.releaseReservation(execution.getAccountedBytes());
        assertThat(execution.getAccountedBytes()).isEqualTo(3072L);
        assertThat(execution.getReservedBytes()).isZero();
        assertThatThrownBy(() -> execution.accountPhoto(0L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsReservationOverflowWithoutReplacingPreviousAmount() {
        var execution = new AdditionalAttachmentExecution(
                UUID.randomUUID().toString(), 1L, 2L, 1, LocalDateTime.now());
        execution.reserve(Long.MAX_VALUE);

        assertThatThrownBy(() -> execution.reserve(1L)).isInstanceOf(ArithmeticException.class);
        assertThat(execution.getReservedBytes()).isEqualTo(Long.MAX_VALUE);
    }
}
