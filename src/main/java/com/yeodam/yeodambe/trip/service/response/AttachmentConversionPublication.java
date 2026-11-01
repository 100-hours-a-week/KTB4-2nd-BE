package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;

public record AttachmentConversionPublication(
        String outboxId,
        String topic,
        String key,
        String payload,
        String topicId,
        int partition,
        long offset,
        Instant brokerTimestamp,
        Integer leaderEpoch
) {
}
