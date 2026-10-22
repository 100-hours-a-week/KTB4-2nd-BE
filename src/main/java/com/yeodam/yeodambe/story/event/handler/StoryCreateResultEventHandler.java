package com.yeodam.yeodambe.story.event.handler;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.event.StoryCreateResultEvent;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.StoryGenerationCompletionService;
import com.yeodam.yeodambe.story.service.StoryGenerationTerminalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@RequiredArgsConstructor
public class StoryCreateResultEventHandler
        implements EventHandler<StoryCreateResultEvent> {

    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationCompletionService completionService;
    private final StoryGenerationTerminalService terminalService;

    @Override
    public EventType eventType() {
        return EventType.STORY_CREATE_RESULT;
    }

    @Override
    public void handle(StoryCreateResultEvent event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());

        if (!supports(event.eventType())) {
            return;
        }

        if (!"story_result".equals(event.type())
                || event.tripId() == null
                || event.tripId() <= 0
                || event.executionId() == null
                || event.executionId().isBlank()
                || event.status() == null
                || event.status() == StoryGenerationAiResponse.Status.QUEUED
                || event.status() == StoryGenerationAiResponse.Status.PROCESSING) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        var job = jobRepository.findByExecutionId(event.executionId())
                .orElse(null);

        if (job == null || job.isFinished()) {
            return;
        }

        if (!job.getTripId().equals(event.tripId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH
            );
        }

        try (var scope = WorkflowMdcScope.job("story_generation", job.getTripId(), job.getExecutionId())) {
            org.slf4j.MDC.put("generation_id", job.getId().toString());
            var response = event.toResponse();

            if (event.status() == StoryGenerationAiResponse.Status.COMPLETED) {
                completionService.complete(job.getId(), response);
            } else {
                terminalService.apply(job.getId(), response);
            }
        }
    }
}