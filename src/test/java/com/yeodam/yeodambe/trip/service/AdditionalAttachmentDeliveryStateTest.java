package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class AdditionalAttachmentDeliveryStateTest {
    @Test
    void 중복_확인에도_최초_시각과_페이로드를_보존한다() {
        var outbox = new AdditionalAttachmentUploadOutbox(1L, "photos_ready", "{}", "request");
        var first = LocalDateTime.of(2026, 10, 15, 12, 0);
        assertThat(outbox.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PENDING);
        outbox.publicationSucceeded(first);
        outbox.publicationSucceeded(first.plusHours(1));
        assertThat(outbox.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(first);
        assertThat(outbox.getPayload()).isEqualTo("{}");
    }
}
