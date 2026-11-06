package com.yeodam.yeodambe.story.service.response;

public record StoryReadContext(
        Long tripId,
        Long ownerId,
        Long storyId
) {
}
