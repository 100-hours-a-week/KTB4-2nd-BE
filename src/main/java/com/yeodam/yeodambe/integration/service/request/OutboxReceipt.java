package com.yeodam.yeodambe.integration.service.request;

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
        Assert.notNull(publishedAt, "Outbox publication time is required");
    }

    public void verify(
            String expectedTopic,
            Long tripId,
            String expectedPayload
    ) {
        Assert.isTrue(Objects.equals(topic, expectedTopic)
                && tripId != null && Objects.equals(key, tripId.toString())
                && Objects.equals(payload, expectedPayload), "Outbox receipt does not match stored event");
    }
}
