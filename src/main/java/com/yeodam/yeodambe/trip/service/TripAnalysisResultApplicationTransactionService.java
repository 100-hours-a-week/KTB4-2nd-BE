package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.user.service.UserStatsService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class TripAnalysisResultApplicationTransactionService {
    private final TripAnalysisExecutionRepository analyses;
    private final TripAnalysisExecutionUploadRepository includedUploads;
    private final AdditionalAttachmentExecutionRepository additions;
    private final AdditionalAttachmentUploadBatchRepository batches;
    private final AdditionalAttachmentUploadOutboxRepository outboxes;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;
    private final AdditionalAttachmentUploadTransactionService uploads;
    private final TripAnalysisResultService classification;
    private final UserStatsService stats;
    private final TripCompletionSnapshotFactory completionSnapshots;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final TripPhotoDeletionReservationService deletions;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean receive(TripCreateResultEvent event, String raw) {
        TripAnalysisExecution execution = lock(event.executionId());
        if (!Objects.equals(execution.getTripId(), event.tripId())) {
            throw new IllegalArgumentException(TripInternalErrorMessage.EVENT_TRIP_EXECUTION_MISMATCH.message());
        }
        LocalDateTime now = uploads.databaseTime();
        if (execution.getResultRawPayload() != null) {
            if (!json.readTree(execution.getResultRawPayload()).equals(json.readTree(raw))) {
                execution.recordConflictingResult(raw);
            }
            return true;
        }
        if (execution.getEndedAt() != null) {
            execution.recordConflictingResult(raw);
            if (execution.isRequiresRemoteTerminationConfirmation()) {
                execution.confirmRemoteTermination(now);
            }
            return true;
        }
        execution.receiveResult(raw, event.status().name(), now);
        if (event.status() == TripCreateResultEvent.Status.COMPLETED) {
            for (AdditionalAttachmentExecution addition : includedAdditions(execution)) {
                addition.analysisApplying();
                publishStatus(addition, null, null, execution);
            }
        } else {
            failAnalysis(execution, ErrorMessage.AI_PROCESSING_FAILED.name(), false, now);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<TripAnalysisExecution> findClaimCandidates() {
        return analyses.findResultsToApply(uploads.databaseTime(), PageRequest.of(0, 100));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TripAnalysisResultClaim> claim(String executionId) {
        TripAnalysisExecution execution = lock(executionId);
        LocalDateTime now = uploads.databaseTime();
        if (!execution.canClaim(now)) {
            return Optional.empty();
        }
        execution.claimResult(UUID.randomUUID().toString(), now.plusMinutes(5));
        return Optional.of(new TripAnalysisResultClaim(executionId, execution.getTripId(), execution.getOwnerId(),
                execution.getResultClaimToken(), execution.getResultRawPayload(), execution.getResultAttempts() + 1));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renew(TripAnalysisResultClaim claim) {
        TripAnalysisExecution execution = lock(claim.executionId());
        LocalDateTime now = uploads.databaseTime();
        if (!execution.ownsResult(claim.claimToken(), claim.attempt(), now)) {
            return false;
        }
        execution.renewResult(now.plusMinutes(5));
        return true;
    }

    @Transactional(readOnly = true)
    public JsonNode validate(TripAnalysisResultClaim claim) {
        TripAnalysisExecution execution = analyses.findById(claim.executionId()).orElseThrow();
        JsonNode result = readResult(claim.rawPayload());
        classification.validateResult(loadExpectedPhotos(execution), result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(TripAnalysisResultClaim claim, JsonNode result, Map<String, String> names) {
        TripAnalysisExecution execution = lock(claim.executionId());
        LocalDateTime now = uploads.databaseTime();
        if (!execution.ownsResult(claim.claimToken(), claim.attempt(), now)) {
            throw new CancellationException();
        }
        JsonNode stored = readResult(execution.getResultRawPayload());
        if (!Objects.equals(claim.rawPayload(), execution.getResultRawPayload()) || !stored.equals(result)) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_ATTACHMENT_LIST_MISMATCH.message());
        }
        List<TripAttachment> photos = loadExpectedPhotos(execution);
        classification.validateResult(photos, stored);
        classification.replaceCompleted(claim.tripId(), claim.userId(), photos, stored, names);
        List<AdditionalAttachmentExecution> included = includedAdditions(execution);
        for (AdditionalAttachmentExecution addition : included) {
            batches.findAllByAdditionIdOrderByBatchNoAsc(addition.getExecutionId())
                    .forEach(AdditionalAttachmentUploadBatch::completeAddition);
            if (!addition.isReservationReleased()) {
                stats.releaseInitialUploadReservation(addition.getUserId(), addition.getReservedBytes());
                addition.releaseReservation(addition.getAccountedBytes());
            }
            addition.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, now);
        }
        execution.finish(TripAnalysisExecution.Status.COMPLETED, null, false, now);
        deletions.applyForInput(execution.getTripId(), photos.stream().map(TripAttachment::getId).collect(Collectors.toSet()));
        stats.refreshFromActiveTrips(execution.getOwnerId());
        TripCompletionSnapshot completion = completionSnapshots.captureCurrent(execution.getTripId(), execution.getOwnerId(),
                AiTaskCompletionType.TRIP_ANALYSIS_APPLIED, execution.getResultRawPayload());
        for (AdditionalAttachmentExecution addition : included) {
            publishStatus(addition, execution.getResultRawPayload(), completion, execution);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(TripAnalysisResultClaim claim, String reason) {
        TripAnalysisExecution execution = lock(claim.executionId());
        LocalDateTime now = uploads.databaseTime();
        if (!execution.ownsResult(claim.claimToken(), claim.attempt(), now)) {
            return;
        }
        execution.retryResult(reason, now);
        if (execution.getStatus() == TripAnalysisExecution.Status.APPLY_BLOCKED) {
            for (AdditionalAttachmentExecution addition : includedAdditions(execution)) {
                addition.analysisBlocked();
                publishStatus(addition, null, null, execution);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TripAnalysisExecution> findExpiredCandidates() {
        return analyses.findExpired(uploads.databaseTime().minusHours(1), PageRequest.of(0, 100));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expire(String executionId) {
        TripAnalysisExecution execution = lock(executionId);
        LocalDateTime now = uploads.databaseTime();
        if (execution.getEndedAt() == null && execution.getResultRawPayload() == null
                && execution.getProcessPublishedAt() != null
                && !execution.getProcessPublishedAt().isAfter(now.minusHours(1))) {
            failAnalysis(execution, ErrorMessage.AI_RESULT_TIMEOUT.name(), true, now);
        }
    }

    private void failAnalysis(TripAnalysisExecution execution, String reason, boolean remoteUnconfirmed, LocalDateTime now) {
        execution.finish(TripAnalysisExecution.Status.FAILED, reason, remoteUnconfirmed, now);
        for (AdditionalAttachmentExecution addition : includedAdditions(execution)) {
            if (addition.recordAnalysisFailure(now)) {
                uploads.failExecution(addition.getExecutionId(), reason);
            } else {
                publishStatus(addition, null, null, execution);
            }
        }
        deletions.applyFinalFailures(execution.getTripId(), expectedPhotoIds(execution));
    }

    private List<AdditionalAttachmentExecution> includedAdditions(TripAnalysisExecution execution) {
        List<AdditionalAttachmentExecution> included = includedUploads.findAllByExecutionIdOrderById(execution.getExecutionId()).stream()
                .map(upload -> additions.findForUpdate(upload.getAdditionId()).orElseThrow()).toList();
        for (AdditionalAttachmentExecution addition : included) {
            entityManager.refresh(addition, LockModeType.PESSIMISTIC_WRITE);
        }
        return included;
    }

    private List<TripAttachment> loadExpectedPhotos(TripAnalysisExecution execution) {
        Set<Long> expected = expectedPhotoIds(execution);
        List<TripAttachment> photos = attachments.findAllForReanalysis(execution.getTripId()).stream()
                .filter(photo -> expected.contains(photo.getId())).toList();
        if (expected.isEmpty() || expected.size() != photos.size()) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_ATTACHMENT_LIST_MISMATCH.message());
        }
        return photos;
    }

    private Set<Long> expectedPhotoIds(TripAnalysisExecution execution) {
        String input = outboxes.findById(execution.getProcessOutboxId()).orElseThrow().getPayload();
        return StreamSupport.stream(json.readTree(input).path("attachments").spliterator(), false)
                .map(photo -> photo.path("trip_attachment_id").asLong()).collect(Collectors.toSet());
    }

    private JsonNode readResult(String raw) {
        return json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .<JsonNode>readValue(raw).path("result");
    }

    private TripAnalysisExecution lock(String executionId) {
        TripAnalysisExecution candidate = analyses.findById(executionId).orElseThrow();
        Trip trip = trips.findAnyForUpdate(candidate.getTripId()).orElseThrow();
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        TripAnalysisExecution execution = analyses.findForUpdate(executionId).orElseThrow();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        return execution;
    }

    private void publishStatus(AdditionalAttachmentExecution addition, String raw, TripCompletionSnapshot completion,
                               TripAnalysisExecution execution) {
        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(addition.getTripId(),
                addition.getExecutionId(), addition.getStatusVersion(), addition.getDisplayStatus(),
                addition.getCurrentStep(), addition.getErrorCode(), raw, execution.getExecutionId(), execution.getStatus(),
                execution.getStatus() == TripAnalysisExecution.Status.APPLY_BLOCKED ? execution.getErrorCode() : null);
        events.publishEvent(new TripProcessingStatusChangedEvent(addition.getUserId(), snapshot, completion));
    }
}
