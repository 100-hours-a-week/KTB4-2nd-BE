package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class AdditionalAttachmentDeliveryStateTest {
    @Test
    void failedDeliveryReleasesClaimAndSchedulesRetry() {
        var outbox = new AdditionalAttachmentUploadOutbox(1L, "photos_ready", "{}", "request");
        var now = LocalDateTime.of(2026, 10, 13, 12, 0);
        outbox.claim("first", now.plusMinutes(1));
        outbox.finishClaim(false, now, "timeout");
        assertThat(outbox.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PENDING);
        assertThat(outbox.getClaimToken()).isNull();
        assertThat(outbox.getNextAttemptAt()).isEqualTo(now.plusSeconds(10));
        outbox.claim("second", now.plusMinutes(2));
        outbox.finishClaim(true, now.plusSeconds(11), null);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(now.plusSeconds(11));
        assertThat(outbox.getLeaseUntil()).isNull();
    }
}
