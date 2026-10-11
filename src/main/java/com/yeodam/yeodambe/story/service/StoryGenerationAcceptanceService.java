package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
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

        outboxRepository.saveAndFlush(
                new StoryGenerationOutbox(job.getId(), payload, requestId)
        );

        return job;
    }
}
