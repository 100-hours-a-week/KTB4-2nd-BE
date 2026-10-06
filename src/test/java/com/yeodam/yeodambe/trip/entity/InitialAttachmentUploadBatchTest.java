package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialAttachmentUploadBatchTest {
    @Test
    void pendingBatchCanStartAndCompleteProcessing() {
        var batch = newBatch();

        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
        batch.startProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        batch.completeProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.COMPLETED);
    }

    @Test
    void pendingBatchCannotSkipProcessingToSuccessOrFailure() {
        var batch = newBatch();

        assertThatThrownBy(batch::completeProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(batch::failProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
    }

    @Test
    void processingBatchCannotStartAgainButCanFail() {
        var batch = newBatch();
        batch.startProcessing();

        assertThatThrownBy(batch::startProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        batch.failProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.FAILED);
    }

    @Test
    void completedBatchRejectsFurtherTransitions() {
        var completed = newBatch();
        completed.startProcessing();
        completed.completeProcessing();

        for (var batch : new InitialAttachmentUploadBatch[]{completed}) {
            var previousStatus = batch.getStatus();
            assertThatThrownBy(batch::startProcessing)
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
            assertThatThrownBy(batch::completeProcessing)
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
            assertThatThrownBy(batch::failProcessing)
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
            assertThat(batch.getStatus()).isEqualTo(previousStatus);
        }
    }

    @Test
    void failedBatchCanRetryButAnalyzingBatchCannotStartAgain() {
        var batch = new InitialAttachmentUploadBatch("upload", "execution", 7L, 42L, 1, 1, true);
        batch.startProcessing();
        batch.failProcessing();
        batch.startProcessing();
        batch.startAnalysis();
        assertThatThrownBy(batch::startProcessing).isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        batch.failAnalysis();
        batch.startProcessing();
        batch.startAnalysis();
        batch.completeAnalysis();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.COMPLETED);
    }

    private InitialAttachmentUploadBatch newBatch() {
        return new InitialAttachmentUploadBatch(
                "upload-id", "execution-id", 7L, 42L, 1, 12, false);
    }
}
