package com.yeodam.yeodambe.trip.service.response;

public record TripKafkaDeliveryClaim(
        String executionId,
        Long tripId,
        Long userId,
        Long batchId,
        String outboxId,
        String claimToken,
        String payload,
        int attempt
) {
}
