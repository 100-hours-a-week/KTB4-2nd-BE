package com.yeodam.yeodambe.story.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;

public record StoryCreateResultEvent(
        String type,

        @JsonProperty("trip_id")
        Long tripId,

        @JsonProperty("execution_id")
        String executionId,

        StoryGenerationAiResponse.Status status,
        StoryGenerationAiResponse.Result result,
        StoryGenerationAiResponse.Failure error
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.STORY_CREATE_RESULT;
    }

    public StoryGenerationAiResponse toResponse() {
        return new StoryGenerationAiResponse(
                tripId,
                executionId,
                status,
                null,
                null,
                result,
                error
        );
    }
}