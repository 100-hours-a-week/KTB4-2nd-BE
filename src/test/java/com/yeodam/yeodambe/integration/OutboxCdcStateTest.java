package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCdcStateTest {
    @Test
    void storyRetainsPayloadAndFirstConfirmation() {
        var outbox = new StoryGenerationOutbox(7L, "{\"type\":\"story\"}", "request");
        outbox.enableCdc(42L, "existing.story");
        var first = LocalDateTime.of(2026, 10, 15, 10, 0);
        outbox.publicationSucceeded(first);
        outbox.publicationSucceeded(first.plusMinutes(1));

        assertThat(outbox.isCdcManaged()).isTrue();
        assertThat(outbox.getTripId()).isEqualTo(42L);
        assertThat(outbox.getTopic()).isEqualTo("existing.story");
        assertThat(outbox.getPayload()).isEqualTo("{\"type\":\"story\"}");
        assertThat(outbox.getPublishedAt()).isEqualTo(first);
        assertThat(outbox.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHED);
    }

    @Test
    void additionalOutboxRetainsBatchAndFirstConfirmation() {
        var outbox = new AdditionalAttachmentUploadOutbox(9L, "photos_ready", "{}", "request");
        assertThat(outbox.isCdcManaged()).isFalse();
        outbox.enableCdc(42L, "existing.update");
        var first = LocalDateTime.of(2026, 10, 15, 10, 0);
        outbox.publicationSucceeded(first);
        outbox.publicationSucceeded(first.plusMinutes(1));

        assertThat(outbox.getUploadBatchId()).isEqualTo(9L);
        assertThat(outbox.getPublishedAt()).isEqualTo(first);
        assertThat(outbox.getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHED);
    }
}
