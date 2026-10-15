package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.Assert;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentUploadOutboxService {

    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;
    private final AdditionalAttachmentExecutionRepository executions;
    private final TripRepository trips;
    private final AdditionalAttachmentAnalysisPreparationService preparation;
    private final TripAttachmentRepository attachments;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional
    public SavedMessages save(
            Long tripId,
            Long userId,
            String uploadId,
            AdditionalAttachmentUploadCompletionService.PreparedQueueBatch prepared,
            String requestId
    ) {
        var execution = lockActiveExecution(tripId, userId, prepared.photosReady().executionId());
        var batch = uploadBatchRepository
                .findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);

        var photosReady = saveIfAbsent(
                batch.getId(),
                prepared.photosReady().type(),
                prepared.photosReady(),
                requestId
        );

        return new SavedMessages(photosReady, enqueueProcessIfReady(execution));
    }

    private AdditionalAttachmentExecution lockActiveExecution(
            Long tripId,
            Long userId,
            String executionId
    ) {
        trips.findOwnedActiveForUpdate(tripId, userId).orElseThrow(InvalidAttachmentUploadException::new);
        var execution = executions.findForUpdate(executionId).orElseThrow();
        entityManager.refresh(execution);
        Assert.isTrue(!execution.isTerminal() && tripId.equals(execution.getTripId())
                && userId.equals(execution.getUserId()), "Outbox execution is not active for this trip");
        return execution;
    }

    private AdditionalAttachmentUploadOutbox saveIfAbsent(
            Long uploadBatchId,
            String messageType,
            Object message,
            String requestId
    ) {
        return outboxRepository
                .findByUploadBatchIdAndMessageType(uploadBatchId, messageType)
                .orElseGet(() -> {
                    var outbox = new AdditionalAttachmentUploadOutbox(
                            uploadBatchId, messageType, objectMapper.writeValueAsString(message), requestId);
                    var batch = uploadBatchRepository.findById(uploadBatchId).orElseThrow();
                    outbox.enableCdc(batch.getTripId(), topics.topicFor(EventType.TRIP_UPDATE_REQUEST));
                    return outboxRepository.saveAndFlush(outbox);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordPublished(
            Long outboxId,
            OutboxReceipt receipt
    ) {
        var candidate = outboxRepository.findById(outboxId).orElseThrow();
        var batch = uploadBatchRepository.findById(candidate.getUploadBatchId()).orElseThrow();
        var trip = trips.findOwnedActiveForUpdate(batch.getTripId(), batch.getUserId()).orElse(null);
        var execution = executions.findForUpdate(batch.getAdditionId()).orElseThrow();
        entityManager.refresh(execution);
        var outbox = outboxRepository.findByIdForUpdate(outboxId).orElseThrow();
        entityManager.refresh(outbox);
        Assert.isTrue(outbox.isCdcManaged(), "Expected a CDC-managed Outbox");
        receipt.verify(outbox.getTopic(), outbox.getTripId(), outbox.getPayload());
        if (outbox.getPublishedAt() != null) {
            return;
        }
        var publishedAt = jdbc.queryForObject("SELECT FROM_UNIXTIME(?)", LocalDateTime.class,
                BigDecimal.valueOf(receipt.publishedAt().toEpochMilli(), 3));
        outbox.publicationSucceeded(publishedAt);
        if ("process".equals(outbox.getMessageType())) {
            execution.recordProcessPublished(publishedAt);
        } else if (trip != null && !execution.isTerminal()) {
            enqueueProcessIfReady(execution);
        }
    }

    private Optional<AdditionalAttachmentUploadOutbox> enqueueProcessIfReady(
            AdditionalAttachmentExecution execution
    ) {
        if (execution.isTerminal() || !execution.isInputClosed() || execution.getProcessOutboxId() != null) {
            return Optional.empty();
        }
        var batches = uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(execution.getExecutionId());
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        var last = batches.getLast();
        if (!last.getLastBatch() || batches.size() != last.getBatchNo()
                || !batches.stream().allMatch(this::isPublishedBatch)) {
            return Optional.empty();
        }
        var message = preparation.prepare(execution.getTripId(), execution.getUserId(), last.getUploadId())
                .processMessage(execution.getExecutionId());
        var process = saveIfAbsent(last.getId(), "process", message,
                last.getRequestId() == null ? execution.getExecutionId() : last.getRequestId());
        execution.attachProcessOutbox(process.getId(), attachments.findAllForReanalysis(execution.getTripId()).size());
        return Optional.of(process);
    }

    private boolean isPublishedBatch(AdditionalAttachmentUploadBatch batch) {
        if (batch.getStatus() != AdditionalAttachmentUploadStatus.PREPARED) {
            return false;
        }
        return outboxRepository.findByUploadBatchIdAndMessageType(batch.getId(), "photos_ready")
                .map(outbox -> outbox.getStatus() == AdditionalAttachmentUploadOutbox.Status.PUBLISHED)
                .orElse(false);
    }

    public record SavedMessages(
            AdditionalAttachmentUploadOutbox photosReady,
            Optional<AdditionalAttachmentUploadOutbox> process
    ) {
    }
}
