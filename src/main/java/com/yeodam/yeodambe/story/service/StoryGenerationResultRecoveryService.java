package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
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
        if (job != null && job.getTripId().equals(tripId) && !job.isFinished()) {
            job.fail(ErrorMessage.STORY_RESULT_PROCESSING_FAILED.name());
            logWorkflowFailed(job);
        }
    }

    private void logWorkflowFailed(StoryGenerationJob job) {
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(),
                job.getCreatedAt(), "failure", job.getErrorCode());
    }
}
