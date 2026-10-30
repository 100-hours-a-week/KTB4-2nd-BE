package com.yeodam.yeodambe.integration.service.request;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Objects;

public record OutboxReceipt(
        String topic,
        String key,
        String payload,
        Instant publishedAt
) {
    public OutboxReceipt {
        Assert.notNull(publishedAt, IntegrationInternalErrorMessage.OUTBOX_PUBLISHED_AT_MISSING.message());
    }

    public void verify(
            String expectedTopic,
            Long tripId,
            String expectedPayload
    ) {
        Assert.isTrue(Objects.equals(topic, expectedTopic)
                && tripId != null && Objects.equals(key, tripId.toString())
                && Objects.equals(payload, expectedPayload), IntegrationInternalErrorMessage.OUTBOX_RECEIPT_MISMATCH.message());
    }
}
