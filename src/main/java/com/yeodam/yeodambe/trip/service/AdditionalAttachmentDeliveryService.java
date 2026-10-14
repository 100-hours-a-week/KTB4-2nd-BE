package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentDeliveryService {
    private final AdditionalAttachmentUploadOutboxRepository outboxRepository;
    private final AdditionalAttachmentUploadBatchRepository batchRepository;
    private final AdditionalAttachmentExecutionRepository executionRepository;
    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadTransactionService transactions;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Claim> claim() {
        for (var candidate : outboxRepository.findPending(transactions.databaseTime(), PageRequest.of(0, 100))) {
            var batch = batchRepository.findById(candidate.getUploadBatchId()).orElseThrow();
            if (tripRepository.findOwnedActiveForUpdate(batch.getTripId(), batch.getUserId()).isEmpty()) continue;
            var execution = executionRepository.findForUpdate(batch.getAdditionId()).orElse(null);
            if (execution == null) continue;
            entityManager.flush();
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            if (execution.isTerminal()) continue;
            var outbox = outboxRepository.findByIdForUpdate(candidate.getId()).orElseThrow();
            entityManager.refresh(outbox, LockModeType.PESSIMISTIC_WRITE);
            var now = transactions.databaseTime();
            if (outbox.getStatus() == AdditionalAttachmentUploadOutbox.Status.PUBLISHED
                    || outbox.getLeaseUntil() != null && outbox.getLeaseUntil().isAfter(now)
                    || outbox.getNextAttemptAt() != null && outbox.getNextAttemptAt().isAfter(now)) continue;
            if ("process".equals(outbox.getMessageType()) && batchRepository
                    .findAllByAdditionIdOrderByBatchNoAsc(execution.getExecutionId()).stream().anyMatch(value ->
                            outboxRepository.findByUploadBatchIdAndMessageType(value.getId(), "photos_ready")
                                    .map(ready -> ready.getStatus() != AdditionalAttachmentUploadOutbox.Status.PUBLISHED)
                                    .orElse(true))) continue;
            outbox.claim(UUID.randomUUID().toString(), now.plusMinutes(1));
            return Optional.of(new Claim(outbox.getId(), execution.getExecutionId(), batch.getTripId(),
                    outbox.getClaimToken(), outbox.getPayload(), outbox.getRequestId()));
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finish(Claim claim, boolean success, String error) {
        var candidate = executionRepository.findById(claim.executionId()).orElseThrow();
        tripRepository.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId()).orElseThrow();
        var execution = executionRepository.findForUpdate(claim.executionId()).orElseThrow();
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        var outbox = outboxRepository.findByIdForUpdate(claim.outboxId()).orElseThrow();
        entityManager.refresh(outbox, LockModeType.PESSIMISTIC_WRITE);
        var now = transactions.databaseTime();
        if (outbox.getStatus() != AdditionalAttachmentUploadOutbox.Status.PUBLISHING
                || !Objects.equals(outbox.getClaimToken(), claim.token())
                || outbox.getLeaseUntil() == null || !outbox.getLeaseUntil().isAfter(now)) return;
        outbox.finishClaim(success, now, error);
        if (success && "process".equals(outbox.getMessageType())) execution.recordProcessPublished(now);
    }

    public record Claim(Long outboxId, String executionId, Long tripId, String token, String payload, String requestId) {}
}
