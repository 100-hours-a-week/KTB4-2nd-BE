package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;

public record TripCompletionSnapshot(
        AiTaskCompletionType eventType,
        TripProcessingStatusResponse.Result result,
        TripListItemResponse listItem,
        String thumbnailKey
) {
}
