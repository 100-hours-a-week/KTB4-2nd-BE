package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoryGenerationResultRecoveryService {
    private final StoryGenerationJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;

    @Transactional
    public void failIdentifiedJob(String message) {
        String executionId;
        Long tripId;
        try {
            var root = objectMapper.readTree(message);
            if (root == null || !"story_result".equals(root.path("type").asString())) {
                return;
            }
            executionId = root.path("execution_id").asString();
            tripId = root.path("trip_id").asLong();
        } catch (JacksonException | IllegalArgumentException failure) {
            return;
        }
        if (executionId == null || executionId.isBlank() || tripId == null || tripId <= 0) {
            return;
        }
        var found = jobRepository.findByExecutionId(executionId).orElse(null);
        if (found == null) {
            return;
        }
        var job = jobRepository.findByIdForUpdate(found.getId()).orElse(null);
        if (job == null) return;
        entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
        if (job.getTripId().equals(tripId) && !job.isFinished()) {
            job.fail(ErrorMessage.STORY_RESULT_PROCESSING_FAILED.name());
            var error = new StoryGenerationStatusResponse.Failure(
                    ErrorMessage.STORY_GENERATION_FAILED.name(),
                    StoryGenerationInternalErrorMessage.GENERATION_FAILED.message());
            var snapshot = new StoryGenerationStatusResponse(
                    job.getId(), job.getStatus(), job.getStatusVersion(), job.getStoryId(), error);
            eventPublisher.publishEvent(new StoryGenerationStatusChangedEvent(
                    job.getUserId(), job.getTripId(), snapshot));
            logWorkflowFailed(job);
        }
    }

    private void logWorkflowFailed(StoryGenerationJob job) {
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(),
                job.getCreatedAt(), "failure", job.getErrorCode());
    }
}
