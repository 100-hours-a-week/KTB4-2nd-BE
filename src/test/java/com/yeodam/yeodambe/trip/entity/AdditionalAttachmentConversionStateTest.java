package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdditionalAttachmentConversionStateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    @Test
    void refusesClaimUntilVerifiedAndKeepsExistingOwnerOnSecondClaim() {
        var batch = batch();
        assertThatThrownBy(() -> batch.claimConversion("first", NOW.plusMinutes(10)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(batch.getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.PENDING);
        assertThat(batch.getWorkerToken()).isNull();
        batch.markVerified();
        batch.claimConversion("first", NOW.plusMinutes(10));
        assertThatThrownBy(() -> batch.claimConversion("second", NOW.plusMinutes(20)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(batch.getWorkerToken()).isEqualTo("first");
        assertThat(batch.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    void onlyCurrentOwnerCanRenewOrScheduleRetry() {
        var batch = batch();
        batch.markVerified();
        batch.claimConversion("first", NOW.plusMinutes(10));
        assertThatThrownBy(() -> batch.renewConversion("other", NOW.plusMinutes(20)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThatThrownBy(() -> batch.retryConversion("other", NOW.plusSeconds(10)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(batch.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(batch.getWorkerToken()).isEqualTo("first");
        batch.renewConversion("first", NOW.plusMinutes(20));
        assertThat(batch.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(20));
        batch.retryConversion("first", NOW.plusSeconds(10));
        assertThat(batch.getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.VERIFIED);
        assertThat(batch.getWorkerToken()).isNull();
        assertThat(batch.getLeaseExpiresAt()).isNull();
        assertThat(batch.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        batch.claimConversion("second", NOW.plusMinutes(30));
        assertThat(batch.getNextAttemptAt()).isNull();
        assertThatThrownBy(() -> batch.releaseConversion("first"))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        batch.releaseConversion("second");
        assertThat(batch.getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.VERIFIED);
        assertThat(batch.getWorkerToken()).isNull();
        assertThat(batch.getLeaseExpiresAt()).isNull();
        assertThat(batch.getNextAttemptAt()).isNull();
    }

    @Test
    void retainsCleanupAndAttemptHistoryAfterRetrySucceeds() {
        var item = new AdditionalAttachmentUploadItem(batch(), 1, "photo.jpg", "image/jpeg", 1024L, "original");
        item.startConversion(NOW);
        item.recordCleanup("[\"temporary-preview\"]", NOW.plusMinutes(10));
        item.conversionFailed(NOW.plusSeconds(10));
        assertThat(item.getConversionAttempts()).isEqualTo(1);
        assertThat(item.getConversionStartedAt()).isNull();
        assertThat(item.getConversionNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(item.getDerivedJson()).isNull();
        item.startConversion(NOW.plusSeconds(10));
        item.conversionCompleted("{\"analyzeKey\":\"analyze\"}");
        assertThat(item.getConversionAttempts()).isEqualTo(2);
        assertThat(item.getDerivedJson()).isEqualTo("{\"analyzeKey\":\"analyze\"}");
        assertThat(item.getConversionStartedAt()).isNull();
        assertThat(item.getConversionNextAttemptAt()).isNull();
        assertThat(item.getCleanupJson()).isEqualTo("[\"temporary-preview\"]");
        assertThat(item.getCleanupStartedAt()).isEqualTo(NOW.plusMinutes(10));
    }

    private AdditionalAttachmentUploadBatch batch() {
        return new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1L, 2L, 1, 1, true);
    }
}
