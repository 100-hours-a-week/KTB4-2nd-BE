package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import org.springframework.context.ApplicationEventPublisher;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentKafkaResultService {
    private final AdditionalAttachmentExecutionRepository executionRepository;
    private final AdditionalAttachmentUploadOutboxRepository outboxRepository;
    private final AdditionalAttachmentUploadBatchRepository batchRepository;
    private final TripRepository tripRepository;
    private final TripAttachmentRepository attachmentRepository;
    private final AdditionalAttachmentUploadTransactionService transactions;
    private final TripAnalysisResultService results;
    private final UserStatsService stats;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final ApplicationEventPublisher eventPublisher;
    private final TripCompletionSnapshotFactory completionSnapshots;
    private final TripPhotoDeletionReservationService deletions;

    private AdditionalAttachmentExecution lock(String id) {
        AdditionalAttachmentExecution candidate = executionRepository.findById(id).orElseThrow();
        tripRepository.findAnyForUpdate(candidate.getTripId()).orElseThrow();
        AdditionalAttachmentExecution execution = executionRepository.findForUpdate(id).orElseThrow();
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        return execution;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean receive(TripCreateResultEvent event, String raw) {
        AdditionalAttachmentExecution candidate = executionRepository.findById(event.executionId()).orElse(null);
        if (candidate == null) {
            return false;
        }
        if (!candidate.getTripId().equals(event.tripId())) {
            return true;
        }
        AdditionalAttachmentExecution execution = lock(event.executionId());
        if (execution.getResultRawPayload() != null) {
            if (!json.readTree(execution.getResultRawPayload()).equals(json.readTree(raw))) {
                execution.recordConflictingResult(raw);
            }
            return true;
        }
        if (execution.isTerminal()) {
            execution.recordConflictingResult(raw);
            if (execution.getRemoteTerminalConfirmedAt() == null) {
                execution.confirmRemoteTermination(transactions.databaseTime());
            }
            return true;
        }
        if (execution.getProcessOutboxId() == null) {
            return true;
        }
        long previousVersion = execution.getStatusVersion();
        execution.receiveResult(raw, event.status().name(), transactions.databaseTime());
        logAiResultReceived(event.tripId(), event.executionId());
        if (event.status() == TripCreateResultEvent.Status.COMPLETED) {
            if (execution.getStatusVersion() != previousVersion) {
                TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                        execution.getTripId(),
                        execution.getExecutionId(),
                        execution.getStatusVersion(),
                        execution.getDisplayStatus(),
                        execution.getCurrentStep(),
                        execution.getErrorCode()
                );
                eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(
                        execution.getUserId(), snapshot));
            }
        } else {
            transactions.failExecution(event.executionId(), ErrorMessage.AI_PROCESSING_FAILED.name());
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<AdditionalAttachmentExecution> findClaimCandidates() {
        return executionRepository.findResultsToApply(transactions.databaseTime(), PageRequest.of(0, 20));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TripAnalysisResultClaim> claim() {
        for (AdditionalAttachmentExecution candidate : findClaimCandidates()) {
            Optional<TripAnalysisResultClaim> claim = claim(candidate.getExecutionId());
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TripAnalysisResultClaim> claim(String executionId) {
        AdditionalAttachmentExecution execution = lock(executionId);
        LocalDateTime now = transactions.databaseTime();
        if (execution.isTerminal() || execution.getResultRawPayload() == null
                || execution.getResultLeaseUntil() != null && execution.getResultLeaseUntil().isAfter(now)
                || execution.getResultNextAttemptAt() != null && execution.getResultNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        if (execution.getResultAttempts() >= 3) {
            transactions.failExecution(executionId, ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name());
            return Optional.empty();
        }
        execution.claimResult(UUID.randomUUID().toString(), now.plusMinutes(5));
        return Optional.of(new TripAnalysisResultClaim(executionId, execution.getTripId(), execution.getUserId(),
                execution.getResultClaimToken(), execution.getResultRawPayload(), execution.getResultAttempts() + 1, null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renew(TripAnalysisResultClaim claim) {
        var execution = lock(claim.executionId());
        if (execution.isTerminal() || !Objects.equals(execution.getResultClaimToken(), claim.claimToken())
                || execution.getResultLeaseUntil() == null || !execution.getResultLeaseUntil().isAfter(transactions.databaseTime())) return false;
        execution.renewResultLease(transactions.databaseTime().plusMinutes(5));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean start(TripAnalysisResultClaim claim) {
        if (!renew(claim)) return false;
        var execution = executionRepository.findById(claim.executionId()).orElseThrow();
        if (execution.getResultAttempts() + 1 != claim.attempt()) return false;
        execution.startResultAttempt(transactions.databaseTime());
        return true;
    }

    @Transactional(readOnly = true)
    public JsonNode validate(TripAnalysisResultClaim claim) {
        List<TripAttachment> photos = loadExpectedPhotos(claim);
        JsonNode result = readResult(claim.rawPayload());
        results.validateResult(photos, result);
        return result;
    }

    private JsonNode readResult(String rawPayload) {
        return json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .<JsonNode>readValue(rawPayload).path("result");
    }

    private List<TripAttachment> loadExpectedPhotos(TripAnalysisResultClaim claim) {
        AdditionalAttachmentExecution execution = executionRepository.findById(claim.executionId()).orElseThrow();
        String payload = outboxRepository.findById(execution.getProcessOutboxId()).orElseThrow().getPayload();
        Set<Long> expected = new HashSet<>();
        json.readTree(payload).path("attachments").forEach(photo -> expected.add(photo.path("trip_attachment_id").asLong()));
        List<TripAttachment> photos = attachmentRepository.findAllForReanalysis(claim.tripId()).stream()
                .filter(photo -> expected.contains(photo.getId())).toList();
        if (!expected.equals(new HashSet<>(photos.stream().map(TripAttachment::getId).toList()))) {
            throw new IllegalStateException(ErrorMessage.AI_RESULT_INTEGRITY_FAILED.name());
        }
        return photos;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(TripAnalysisResultClaim claim, JsonNode result, Map<String, String> names) {
        if (!renew(claim)) {
            throw new java.util.concurrent.CancellationException();
        }
        AdditionalAttachmentExecution execution = executionRepository.findById(claim.executionId()).orElseThrow();
        if (!Objects.equals(execution.getResultRawPayload(), claim.rawPayload())) {
            throw new IllegalStateException(ErrorMessage.AI_RESULT_INTEGRITY_FAILED.name());
        }
        List<TripAttachment> photos = loadExpectedPhotos(claim);
        results.validateResult(photos, readResult(claim.rawPayload()));
        results.replaceCompleted(claim.tripId(), claim.userId(), photos, result, names);
        batchRepository.findAllByAdditionIdOrderByBatchNoAsc(claim.executionId()).forEach(value -> value.completeAddition());
        if (!execution.isReservationReleased()) {
            stats.releaseInitialUploadReservation(claim.userId(), execution.getReservedBytes());
            execution.releaseReservation(execution.getAccountedBytes());
        }
        execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, transactions.databaseTime());
        deletions.applyForInput(claim.tripId(), photos.stream().map(TripAttachment::getId).collect(Collectors.toSet()));
        stats.refreshFromActiveTrips(claim.userId());
        TripCompletionSnapshot completion = completionSnapshots.captureCurrent(claim.tripId(), claim.userId(),
                com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType.TRIP_ANALYSIS_APPLIED,
                execution.getResultRawPayload());
        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                execution.getTripId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                execution.getDisplayStatus(),
                execution.getCurrentStep(),
                execution.getErrorCode(),
                execution.getResultRawPayload()
        );
        eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(execution.getUserId(), snapshot, completion));
        logWorkflowCompleted(claim, execution);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(TripAnalysisResultClaim claim, boolean permanent, String reason) {
        if (!renew(claim)) return;
        var execution = executionRepository.findById(claim.executionId()).orElseThrow();
        execution.recordResultFailure(reason, transactions.databaseTime());
        if (permanent || execution.getResultAttempts() >= 3)
            transactions.failExecution(claim.executionId(), permanent ? ErrorMessage.AI_RESULT_INTEGRITY_FAILED.name()
                    : ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name());
        else execution.retryResult(transactions.databaseTime().plusSeconds(10));
    }

    @Transactional(readOnly = true)
    public List<AdditionalAttachmentExecution> findExpiredCandidates() {
        LocalDateTime now = transactions.databaseTime();
        return executionRepository.findExpired(now.minusMinutes(10), now.minusHours(1), PageRequest.of(0, 100));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expire() {
        for (AdditionalAttachmentExecution candidate : findExpiredCandidates()) {
            expire(candidate.getExecutionId());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expire(String executionId) {
        AdditionalAttachmentExecution execution = lock(executionId);
        LocalDateTime now = transactions.databaseTime();
        if (execution.isTerminal() || execution.getResultRawPayload() != null) {
            return;
        }
        if (!execution.isInputClosed() && !execution.getLastUploadActivityAt().isAfter(now.minusMinutes(10))) {
            transactions.failExecution(executionId, ErrorMessage.UPLOAD_TIMEOUT.name());
        } else if (execution.getProcessPublishedAt() != null && !execution.getProcessPublishedAt().isAfter(now.minusHours(1))) {
            transactions.failExecution(executionId, ErrorMessage.AI_RESULT_TIMEOUT.name());
        }
    }

    private void logAiResultReceived(Long tripId, String executionId) {
        WorkflowLog.event("trip_reanalysis", tripId, executionId, "ai_result_received");
    }

    private void logWorkflowCompleted(TripAnalysisResultClaim claim, AdditionalAttachmentExecution execution) {
        WorkflowLog.finishedFromDatabase("trip_reanalysis", claim.tripId(), claim.executionId(),
                execution.getCreatedAt(), execution.getEndedAt(), "success", null);
    }
}
