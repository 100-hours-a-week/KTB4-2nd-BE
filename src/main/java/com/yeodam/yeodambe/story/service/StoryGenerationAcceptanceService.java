package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoryGenerationAcceptanceService {

    private final StoryGenerationJobService jobService;
    private final StoryGenerationMessageService messageService;
    private final StoryGenerationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public StoryGenerationJob accept(
            Long userId,
            Long tripId,
            StoryGenerationRequest request,
            String requestId
    ) {
        StoryGenerationJob job = jobService.create(userId, tripId, request);
        var message = messageService.assemble(job);
        String payload = objectMapper.writeValueAsString(message);

        try (var scope = WorkflowMdcScope.job("story_generation", tripId, job.getExecutionId());
             var timing = startOutboxPersistenceLogging(job.getId())) {
            logWorkflowStarted(job.getId());
            var outbox = new StoryGenerationOutbox(job.getId(), payload, requestId);
            outbox.enableCdc(tripId, topics.topicFor(EventType.STORY_CREATE_REQUEST));
            outboxRepository.saveAndFlush(outbox);
            logAiRequestEnqueued(timing);
        }
        var snapshot = new StoryGenerationStatusResponse(
                job.getId(), job.getStatus(), job.getStatusVersion(), job.getStoryId(), null
        );
        eventPublisher.publishEvent(new StoryGenerationStatusChangedEvent(
                job.getUserId(), job.getTripId(), snapshot
        ));
        return job;
    }

    private WorkflowLog.Stage startOutboxPersistenceLogging(Long generationId) {
        return WorkflowLog.stage("outbox_persistence")
                .field("message_type", "story")
                .field("generation_id", generationId);
    }

    private void logWorkflowStarted(Long generationId) {
        WorkflowLog.stage("workflow_started")
                .field("generation_id", generationId)
                .committed("workflow_started");
    }

    private void logAiRequestEnqueued(WorkflowLog.Stage timing) {
        timing.committed("ai_request_enqueued");
    }
}
