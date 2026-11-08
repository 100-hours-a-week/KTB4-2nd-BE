package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryGenerationCompletionService {
    private final StoryGenerationPreparationService preparationService;
    private final StoryContentStorageService contentStorageService;
    private final StoryGenerationFinalizationService finalizationService;
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationOutboxRepository outboxRepository;

    public void complete(Long generationId, StoryGenerationAiResponse response) {
        Optional<StoryContentDocument> prepared = preparationService.prepareContent(generationId, response);
        if (prepared.isEmpty()) {
            return;
        }
        StoryContentDocument content = prepared.orElseThrow();
        StoryGenerationJob job = jobRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));
        StoryGenerationOutbox outbox = outboxRepository.findByGenerationId(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));
        try (WorkflowMdcScope scope = WorkflowMdcScope.job(
                "story_generation", job.getTripId(), job.getExecutionId(), outbox.getRequestId()
        )) {
            logAiResultReceived(generationId);
            if (content.getInitialContentHash() == null) {
                contentStorageService.insertIfAbsent(content);
            } else {
                contentStorageService.insertPreparedIfAbsent(content);
            }
            finalizationService.finish(generationId, content.getId());
        }
    }

    private void logAiResultReceived(Long generationId) {
        WorkflowLog.stage("ai_result_received")
                .field("generation_id", generationId)
                .committed("ai_result_received");
    }
}
