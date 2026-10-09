package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialAttachmentUploadBatchTest {
    @Test
    void 대기_배치는_처리를_시작하고_완료할_수_있다() {
        var batch = newBatch();

        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
        batch.startProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        batch.completeProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.COMPLETED);
    }

    @Test
    void 대기_배치는_처리_단계를_건너뛰어_성공이나_실패로_전환할_수_없다() {
        var batch = newBatch();

        assertThatThrownBy(batch::completeProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(batch::failProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
    }

    @Test
    void 처리_중인_배치는_다시_시작할_수_없지만_실패로_전환할_수_있다() {
        var batch = newBatch();
        batch.startProcessing();

        assertThatThrownBy(batch::startProcessing)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        batch.failProcessing();
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.FAILED);
    }

    @Test
    void 완료된_배치는_추가_상태_전환을_거부한다() {
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
    void 실패한_배치는_재시도할_수_있지만_분석_중인_배치는_다시_시작할_수_없다() {
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
