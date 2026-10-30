package com.yeodam.yeodambe.user.service.response;

public record UserStatsResponse(
        long tripCount,
        long attachmentCount,
        long storyCount,
        long storageUsedBytes,
        long storageMaximumBytes
) {
}
