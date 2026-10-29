package com.yeodam.yeodambe.trip.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStatusVersionTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Test
    void creationVersionsTheDisplayStatusAndStepTogether() {
        var execution = new TripCreationExecution("creation", 1L, 2L, 10);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, null)).isFalse();
        assertThat(execution.getStatusVersion()).isEqualTo(1);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isTrue();
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isFalse();
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        assertThat(execution.getCurrentStep()).isEqualTo("EMBEDDING");

        execution.recordProcessPublished(now);
        assertThat(execution.getCurrentStep()).isEqualTo("EMBEDDING");
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.FINALIZING, null)).isTrue();
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isFalse();
        execution.receiveResult("{}", "COMPLETED", now);
        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(execution.getCurrentStep()).isNull();
        assertThat(execution.getStatusVersion()).isEqualTo(3);

        execution.finish(TripCreationExecution.Status.COMPLETED, null, now);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "LATE")).isFalse();
        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.COMPLETED);
        assertThat(execution.getStatusVersion()).isEqualTo(4);
    }

    @Test
    void additionVersionsTheDisplayStatusAndStepTogether() {
        var execution = new AdditionalAttachmentExecution("addition", 1L, 2L, 10, now);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, null)).isFalse();
        assertThat(execution.getStatusVersion()).isEqualTo(1);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isTrue();
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isFalse();
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        assertThat(execution.getCurrentStep()).isEqualTo("EMBEDDING");

        execution.recordProcessPublished(now);
        assertThat(execution.getCurrentStep()).isEqualTo("EMBEDDING");
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.FINALIZING, null)).isTrue();
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING")).isFalse();
        execution.receiveResult("{}", "COMPLETED", now);
        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(execution.getCurrentStep()).isNull();
        assertThat(execution.getStatusVersion()).isEqualTo(3);

        execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, now);
        assertThat(execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "LATE")).isFalse();
        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.COMPLETED);
        assertThat(execution.getStatusVersion()).isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(value = TripCreationExecution.Status.class,
            names = {"COMPLETED", "FAILED", "CANCELED"})
    void creationPreservesItsTerminalStateAgainstLateCallbacks(TripCreationExecution.Status status) {
        var execution = new TripCreationExecution("creation", 1L, 2L, 10);
        execution.finish(status, "original", now);

        execution.closeInput();
        execution.recordProcessPublished(now.plusSeconds(1));
        execution.receiveResult("late", "COMPLETED", now.plusSeconds(1));
        execution.finish(TripCreationExecution.Status.FAILED, "late", now.plusSeconds(1));

        assertThat(execution.getStatus()).isEqualTo(status);
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        assertThat(execution.getErrorCode()).isEqualTo("original");
        assertThat(execution.getEndedAt()).isEqualTo(now);
        assertThat(execution.getResultRawPayload()).isNull();
        assertThat(execution.isInputClosed()).isFalse();
    }

    @Test
    void creationKeepsTheFirstResultWhenItIsRedelivered() {
        var execution = new TripCreationExecution("creation", 1L, 2L, 10);
        execution.receiveResult("first", "COMPLETED", now);
        execution.receiveResult("second", "FAILED", now.plusSeconds(1));

        assertThat(execution.getResultRawPayload()).isEqualTo("first");
        assertThat(execution.getResultAiStatus()).isEqualTo("COMPLETED");
        assertThat(execution.getResultReceivedAt()).isEqualTo(now);
        assertThat(execution.getStatusVersion()).isEqualTo(2);
    }

    @Test
    void creationIncrementsForTransitionsButNotRepeatedCallbacks() {
        var execution = new TripCreationExecution("creation", 1L, 2L, 10);
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.closeInput();
        execution.closeInput();
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.recordProcessPublished(now);
        execution.recordProcessPublished(now.plusSeconds(1));
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.receiveResult("{}", "COMPLETED", now);
        execution.receiveResult("{}", "COMPLETED", now);
        execution.recordProcessPublished(now);
        assertThat(execution.getStatus()).isEqualTo(TripCreationExecution.Status.RESULT_APPLYING);
        assertThat(execution.getStatusVersion()).isEqualTo(2);

        execution.finish(TripCreationExecution.Status.COMPLETED, null, now);
        execution.finish(TripCreationExecution.Status.COMPLETED, null, now);
        assertThat(execution.getStatusVersion()).isEqualTo(3);
    }

    @Test
    void additionIncrementsForTransitionsAndPreservesTheTerminalState() {
        var execution = new AdditionalAttachmentExecution("addition", 1L, 2L, 10, now);
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.closeInput();
        execution.closeInput();
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.recordProcessPublished(now);
        execution.recordProcessPublished(now.plusSeconds(1));
        assertThat(execution.getStatusVersion()).isEqualTo(1);

        execution.receiveResult("{}", "COMPLETED", now);
        execution.receiveResult("{}", "COMPLETED", now);
        assertThat(execution.getStatusVersion()).isEqualTo(2);

        execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, now);
        execution.closeInput();
        execution.recordProcessPublished(now);
        execution.receiveResult("late", "FAILED", now);
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, "LATE_FAILURE", now);

        assertThat(execution.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.COMPLETED);
        assertThat(execution.getStatusVersion()).isEqualTo(3);
        assertThat(execution.getErrorCode()).isNull();
    }
}
