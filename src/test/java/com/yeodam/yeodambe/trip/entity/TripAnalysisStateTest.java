package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TripAnalysisStateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);

    @Test
    void 결과는_임대와_재시도_대기가_끝나야_새_소유자가_선점한다() {
        TripAnalysisExecution execution = new TripAnalysisExecution("analysis", 1L, 2L);
        assertThat(execution.canClaim(NOW)).isFalse();
        execution.receiveResult("{\"places\":[]}", "COMPLETED", NOW);
        assertThat(execution.canClaim(NOW)).isTrue();
        execution.claimResult("first", NOW.plusSeconds(30));
        assertThat(execution.canClaim(NOW.plusSeconds(29))).isFalse();
        assertThat(execution.ownsResult("first", 1, NOW)).isTrue();
        assertThat(execution.ownsResult("other", 1, NOW)).isFalse();
        assertThat(execution.ownsResult("first", 2, NOW)).isFalse();
        execution.renewResult(NOW.plusSeconds(40));
        assertThat(execution.ownsResult("first", 1, NOW.plusSeconds(39))).isTrue();
        assertThat(execution.ownsResult("first", 1, NOW.plusSeconds(40))).isFalse();
        assertThat(execution.canClaim(NOW.plusSeconds(40))).isTrue();

        execution.retryResult("database unavailable", NOW.plusSeconds(40));
        assertThat(execution.getResultClaimToken()).isNull();
        assertThat(execution.getResultLeaseUntil()).isNull();
        assertThat(execution.canClaim(NOW.plusSeconds(49))).isFalse();
        assertThat(execution.canClaim(NOW.plusSeconds(50))).isTrue();
        execution.claimResult("second", NOW.plusMinutes(1));
        assertThat(execution.ownsResult("first", 1, NOW.plusSeconds(50))).isFalse();
        assertThat(execution.ownsResult("second", 2, NOW.plusSeconds(50))).isTrue();
    }

    @Test
    void 반영_실패_세_번이면_원문과_최초_충돌을_보존하고_자동_선점을_멈춘다() {
        TripAnalysisExecution execution = new TripAnalysisExecution("analysis", 1L, 2L);
        execution.recordProcessPublished(NOW);
        execution.recordProcessPublished(NOW.plusSeconds(1));
        execution.receiveResult("original", "COMPLETED", NOW);
        execution.recordConflictingResult("first conflict");
        execution.recordConflictingResult("second conflict");
        for (int attempt = 0; attempt < 3; attempt++) {
            execution.claimResult("worker", NOW.plusMinutes(1));
            execution.retryResult("failure " + attempt, NOW.plusSeconds(attempt));
        }

        assertThat(execution.getProcessPublishedAt()).isEqualTo(NOW);
        assertThat(execution.getStatus()).isEqualTo(TripAnalysisExecution.Status.APPLY_BLOCKED);
        assertThat(execution.getErrorCode()).isEqualTo(ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name());
        assertThat(execution.getResultAttempts()).isEqualTo(3);
        assertThat(execution.getResultRawPayload()).isEqualTo("original");
        assertThat(execution.getResultConflicts()).isEqualTo("first conflict");
        assertThat(execution.getResultFailureReason()).isEqualTo("failure 2");
        assertThat(execution.getEndedAt()).isNull();
        assertThat(execution.canClaim(NOW.plusDays(1))).isFalse();
    }

    @Test
    void 로컬_종료는_선점을_풀고_원격_종료_미확인과_결과를_보존한다() {
        TripAnalysisExecution execution = new TripAnalysisExecution("analysis", 1L, 2L);
        execution.receiveResult("original", "COMPLETED", NOW);
        execution.claimResult("worker", NOW.plusMinutes(1));
        execution.finish(TripAnalysisExecution.Status.CANCELED, null, true, NOW);

        assertThat(execution.getEndedAt()).isEqualTo(NOW);
        assertThat(execution.getResultClaimToken()).isNull();
        assertThat(execution.getResultLeaseUntil()).isNull();
        assertThat(execution.getResultRawPayload()).isEqualTo("original");
        assertThat(execution.isRequiresRemoteTerminationConfirmation()).isTrue();
        assertThat(execution.getRemoteTerminalConfirmedAt()).isNull();
        assertThat(execution.canClaim(NOW)).isFalse();
        execution.confirmRemoteTermination(NOW.plusSeconds(1));
        assertThat(execution.getRemoteTerminalConfirmedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void 묶음은_수락한_사용자를_구분하고_첫_분석_실패만_재대기한다() {
        AdditionalAttachmentExecution addition = new AdditionalAttachmentExecution("addition", 1L, 2L, 1, NOW);
        assertThat(addition.getProcessingMode()).isEqualTo(AdditionalAttachmentExecution.ProcessingMode.LEGACY);
        assertThat(addition.acceptedBy(2L)).isTrue();
        addition.acceptV3(3L);
        assertThat(addition.acceptedBy(2L)).isFalse();
        assertThat(addition.acceptedBy(3L)).isTrue();
        addition.analysisRequested();
        addition.analysisApplying();
        assertThat(addition.getDisplayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        long version = addition.getStatusVersion();
        addition.analysisBlocked();
        assertThat(addition.getStatusVersion()).isEqualTo(version + 1);
        assertThat(addition.recordAnalysisFailure(NOW)).isFalse();
        assertThat(addition.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.IMAGE_PROCESSING);
        assertThat(addition.getDisplayStatus()).isEqualTo(TripDisplayStatus.PROCESSING);
        assertThat(addition.getCurrentStep()).isNull();
        assertThat(addition.getQuarantinedAt()).isNull();
        addition.analysisRequested();
        assertThat(addition.recordAnalysisFailure(NOW.plusSeconds(1))).isTrue();
        assertThat(addition.getAnalysisFailureCount()).isEqualTo(2);
        assertThat(addition.getQuarantinedAt()).isEqualTo(NOW.plusSeconds(1));
        addition.confirmRemoteTermination(NOW.plusSeconds(2));
        assertThat(addition.getRemoteTerminalConfirmedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void 초기_실행은_요청을_만들고_결과_없이_실패하거나_취소한_경우만_원격_확인이_필요하다() {
        TripCreationExecution canceled = new TripCreationExecution("canceled", 1L, 2L, 1);
        canceled.enqueueProcess("{\"attachments\":[]}");
        canceled.finish(TripCreationExecution.Status.CANCELED, null, NOW);
        assertThat(canceled.isRequiresRemoteTerminationConfirmation()).isTrue();
        canceled.confirmRemoteTermination(NOW.plusSeconds(1));
        assertThat(canceled.getRemoteTerminalConfirmedAt()).isEqualTo(NOW.plusSeconds(1));
        canceled.finish(TripCreationExecution.Status.COMPLETED, null, NOW.plusSeconds(2));
        assertThat(canceled.getStatus()).isEqualTo(TripCreationExecution.Status.CANCELED);
        assertThat(canceled.isRequiresRemoteTerminationConfirmation()).isTrue();

        TripCreationExecution noRequest = new TripCreationExecution("no-request", 1L, 2L, 1);
        noRequest.finish(TripCreationExecution.Status.FAILED, null, NOW);
        assertThat(noRequest.isRequiresRemoteTerminationConfirmation()).isFalse();
        TripCreationExecution resultReceived = new TripCreationExecution("received", 1L, 2L, 1);
        resultReceived.enqueueProcess("{}");
        resultReceived.receiveResult("{}", "FAILED", NOW);
        resultReceived.finish(TripCreationExecution.Status.FAILED, null, NOW);
        assertThat(resultReceived.isRequiresRemoteTerminationConfirmation()).isFalse();
        TripCreationExecution completed = new TripCreationExecution("completed", 1L, 2L, 1);
        completed.enqueueProcess("{}");
        completed.finish(TripCreationExecution.Status.COMPLETED, null, NOW);
        assertThat(completed.isRequiresRemoteTerminationConfirmation()).isFalse();
    }

    @Test
    void 삭제_예약은_적용_시각을_기록한다() {
        TripPhotoDeletionReservation reservation = new TripPhotoDeletionReservation(1L, 2L, 3L, "request", "[]");
        assertThat(reservation.getStatus()).isEqualTo(TripPhotoDeletionReservation.Status.PENDING);
        reservation.apply(NOW);
        assertThat(reservation.getStatus()).isEqualTo(TripPhotoDeletionReservation.Status.APPLIED);
        assertThat(reservation.getAppliedAt()).isEqualTo(NOW);
    }
}
