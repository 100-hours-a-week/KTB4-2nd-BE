package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.integration.service.request.PhotoProcessMessage;
import com.yeodam.yeodambe.integration.service.request.PhotosReadyMessage;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecutionUpload;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripAnalysisExecutionTransactionService {
    private final TripRepository trips;
    private final AdditionalAttachmentExecutionRepository additions;
    private final AdditionalAttachmentUploadBatchRepository batches;
    private final AdditionalAttachmentUploadItemRepository items;
    private final TripAttachmentRepository attachments;
    private final TripAnalysisExecutionRepository analyses;
    private final TripAnalysisExecutionUploadRepository includedUploads;
    private final AdditionalAttachmentUploadOutboxRepository outboxes;
    private final AdditionalAttachmentAnalysisPreparationService preparation;
    private final KafkaTopicProperties topics;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TripCreationExecutionRepository creations;

    @Transactional(readOnly = true)
    public List<RemoteTermination> findPendingRemoteTerminations(Long tripId) {
        List<RemoteTermination> pending = new ArrayList<>();
        analyses.findUnconfirmedRemoteTerminationsByTripId(tripId).stream()
                .map(execution -> new RemoteTermination(execution.getExecutionId(), false)).forEach(pending::add);
        additions.findUnconfirmedLegacyTimeouts(tripId).stream()
                .map(execution -> new RemoteTermination(execution.getExecutionId(), true)).forEach(pending::add);
        creations.findUnconfirmedRemoteTerminations(tripId).stream()
                .map(execution -> new RemoteTermination(execution.getExecutionId(), false, true)).forEach(pending::add);
        return List.copyOf(pending);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmRemoteTermination(Long tripId, RemoteTermination predecessor) {
        Trip trip = trips.findAnyForUpdate(tripId).orElse(null);
        if (trip == null) {
            return;
        }
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        if (predecessor.initial()) {
            TripCreationExecution execution = creations.findForUpdate(predecessor.executionId()).orElseThrow();
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            if (execution.getTripId().equals(tripId) && execution.isTerminal()
                    && execution.isRequiresRemoteTerminationConfirmation()) {
                execution.confirmRemoteTermination(now);
            }
        } else if (predecessor.legacy()) {
            AdditionalAttachmentExecution execution = additions.findForUpdate(predecessor.executionId()).orElseThrow();
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            if (execution.getTripId().equals(tripId) && execution.isTerminal()
                    && execution.getProcessingMode() == AdditionalAttachmentExecution.ProcessingMode.LEGACY) {
                execution.confirmRemoteTermination(now);
            }
        } else {
            TripAnalysisExecution execution = analyses.findForUpdate(predecessor.executionId()).orElseThrow();
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            if (execution.getTripId().equals(tripId) && execution.getEndedAt() != null
                    && execution.isRequiresRemoteTerminationConfirmation()) {
                execution.confirmRemoteTermination(now);
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<String> startNext(Long tripId) {
        Trip trip = trips.findActiveForUpdate(tripId).orElse(null);
        if (trip == null) {
            return Optional.empty();
        }
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED
                || additions.hasActiveLegacyExecution(tripId)
                || !findPendingRemoteTerminations(tripId).isEmpty()
                || analyses.findActiveForUpdate(tripId).isPresent()) {
            return Optional.empty();
        }
        List<AdditionalAttachmentExecution> ready = additions.findAnalysisCandidatesForUpdate(tripId).stream()
                .filter(this::isPrepared).toList();
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        String executionId = UUID.randomUUID().toString();
        Set<String> additionIds = ready.stream().map(AdditionalAttachmentExecution::getExecutionId)
                .collect(Collectors.toSet());
        List<TripAttachment> photos = selectInputPhotos(tripId, additionIds);
        TripPhotoAnalysisRequest input = preparation.prepareInput(trip, executionId, photos);
        TripAnalysisExecution execution = analyses.saveAndFlush(
                new TripAnalysisExecution(executionId, tripId, trip.getUserId()));
        for (AdditionalAttachmentExecution addition : ready) {
            includedUploads.save(new TripAnalysisExecutionUpload(executionId, addition.getExecutionId()));
            addition.analysisRequested();
        }
        saveMessages(execution, ready, input);
        return Optional.of(executionId);
    }

    private boolean isPrepared(AdditionalAttachmentExecution addition) {
        entityManager.refresh(addition, LockModeType.PESSIMISTIC_WRITE);
        List<AdditionalAttachmentUploadBatch> accepted = batches
                .findAllByAdditionIdOrderByBatchNoAsc(addition.getExecutionId());
        if (accepted.isEmpty() || !accepted.getLast().getLastBatch()
                || accepted.stream().anyMatch(batch -> batch.getStatus() != AdditionalAttachmentUploadStatus.PREPARED)) {
            return false;
        }
        List<AdditionalAttachmentUploadItem> acceptedItems = items.findAdditionItems(addition.getExecutionId());
        return acceptedItems.size() == addition.getTotalAttachmentCount()
                && acceptedItems.stream().allMatch(item -> item.getTripAttachmentId() != null);
    }

    private List<TripAttachment> selectInputPhotos(Long tripId, Set<String> additionIds) {
        List<TripAttachment> photos = attachments.findAllForReanalysis(tripId);
        List<Long> photoIds = photos.stream().map(TripAttachment::getId).toList();
        Map<Long, AdditionalAttachmentUploadItem> additionalItems = new HashMap<>();
        for (AdditionalAttachmentUploadItem item : items.findAllByTripAttachmentIdIn(photoIds)) {
            additionalItems.put(item.getTripAttachmentId(), item);
        }
        return photos.stream().filter(photo -> {
            AdditionalAttachmentUploadItem item = additionalItems.get(photo.getId());
            return item == null || item.getBatch().getStatus() == AdditionalAttachmentUploadStatus.COMPLETED
                    || additionIds.contains(item.getBatch().getAdditionId());
        }).toList();
    }

    private void saveMessages(
            TripAnalysisExecution execution,
            List<AdditionalAttachmentExecution> ready,
            TripPhotoAnalysisRequest input
    ) {
        Map<Long, TripPhotoAnalysisRequest.Photo> photos = input.attachments().stream()
                .collect(Collectors.toMap(TripPhotoAnalysisRequest.Photo::tripAttachmentId, photo -> photo));
        int batchNo = 0;
        for (AdditionalAttachmentExecution addition : ready) {
            if (addition.getAnalysisFailureCount() == 0) {
                for (AdditionalAttachmentUploadBatch batch : batches
                        .findAllByAdditionIdOrderByBatchNoAsc(addition.getExecutionId())) {
                    batchNo++;
                    List<PhotosReadyMessage.Attachment> batchPhotos = items
                            .findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                            .map(item -> photos.get(item.getTripAttachmentId()))
                            .map(photo -> new PhotosReadyMessage.Attachment(photo.tripAttachmentId(), photo.analyzeStorageKey()))
                            .toList();
                    PhotosReadyMessage message = PhotosReadyMessage.create(execution.getTripId(),
                            execution.getExecutionId(), batchNo, batchPhotos);
                    saveOutbox(execution, batch.getId(), batchNo, "photos_ready", message);
                }
            }
        }
        AdditionalAttachmentUploadOutbox process = saveOutbox(execution, null, 0, "process",
                PhotoProcessMessage.from(execution.getTripId(), execution.getExecutionId(), input));
        execution.attachProcessOutbox(process.getId());
    }

    private AdditionalAttachmentUploadOutbox saveOutbox(
            TripAnalysisExecution execution,
            Long batchId,
            int batchNo,
            String type,
            Object message
    ) {
        AdditionalAttachmentUploadOutbox outbox = new AdditionalAttachmentUploadOutbox(batchId,
                execution.getExecutionId(), batchNo, type, json.writeValueAsString(message), execution.getExecutionId());
        outbox.enableCdc(execution.getTripId(), topics.topicFor(EventType.TRIP_UPDATE_REQUEST));
        return outboxes.saveAndFlush(outbox);
    }

    public record RemoteTermination(
            String executionId,
            boolean legacy,
            boolean initial
    ) {
        public RemoteTermination(String executionId, boolean legacy) {
            this(executionId, legacy, false);
        }
    }
}
