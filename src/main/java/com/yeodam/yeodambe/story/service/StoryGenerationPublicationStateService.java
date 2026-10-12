package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class StoryGenerationPublicationStateService {

    private final StoryGenerationOutboxRepository outboxRepository;
    private final StoryGenerationJobRepository jobRepository;

    public StoryGenerationOutbox begin(Long generationId) {
        StoryGenerationOutbox outbox = outboxRepository
                .findByGenerationId(generationId)
                .orElseThrow();

        outbox.beginPublishing();

        return outbox;
    }

    public void succeeded(Long outboxId, LocalDateTime publishedAt) {
        StoryGenerationOutbox outbox = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        outbox.publicationSucceeded(publishedAt);
    }

    public void failed(Long outboxId, String error) {
        StoryGenerationOutbox outbox = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        var job = jobRepository
                .findByIdForUpdate(outbox.getGenerationId())
                .orElseThrow();

        outbox.publicationFailed(error);
        job.fail(ErrorMessage.INTERNAL_SERVER_ERROR.name());
    }
}