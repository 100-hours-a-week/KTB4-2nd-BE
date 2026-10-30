package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class StoryGenerationPublicationStateService {

    private final StoryGenerationOutboxRepository outboxRepository;
    private final StoryGenerationJobRepository jobRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public void recordPublished(
            Long outboxId,
            OutboxReceipt receipt
    ) {
        var candidate = outboxRepository.findById(outboxId).orElseThrow();
        var job = jobRepository.findByIdForUpdate(candidate.getGenerationId()).orElseThrow();
        var outbox = outboxRepository.findByIdForUpdate(outboxId).orElseThrow();
        entityManager.refresh(outbox);
        Assert.isTrue(outbox.isCdcManaged(), EventInternalErrorMessage.OUTBOX_NOT_CDC_MANAGED.message());
        receipt.verify(outbox.getTopic(), outbox.getTripId(), outbox.getPayload());
        var publishedAt = jdbc.queryForObject("SELECT FROM_UNIXTIME(?)", LocalDateTime.class,
                BigDecimal.valueOf(receipt.publishedAt().toEpochMilli(), 3));
        boolean firstPublication = outbox.getPublishedAt() == null;
        outbox.publicationSucceeded(publishedAt);
        if (firstPublication) {
            logAiRequestObserved(job);
        }
    }

    private void logAiRequestObserved(StoryGenerationJob job) {
        WorkflowLog.deliveryObserved("story_generation", job.getTripId(), job.getExecutionId(), "story");
    }
}
