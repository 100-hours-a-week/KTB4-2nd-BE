package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution.ProcessingMode;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentAdmissionTransactionService {
    private static final long MAX_ADDITION_BYTES = 3L * 1024 * 1024 * 1024;

    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadItemRepository uploadItemRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final AdditionalAttachmentExecutionRepository executionRepository;
    private final UserStatsService userStatsService;
    private final JdbcTemplate jdbcTemplate;
    private final StoryGenerationJobRepository storyGenerationJobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AttachmentConversionAdmissionService conversionAdmission;
    private final TripEditAuthorizationService authorization;
    private final EntityManager entityManager;
    private final UserRepository users;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdditionalAttachmentUploadBatch prepareBatch(
            Long tripId,
            Long actorId,
            AdditionalAttachmentUploadUrlRequest request
    ) {
        long batchBytes = validateRequest(request);
        Trip trip = requireCompletedTrip(tripId);
        Long ownerId = trip.getUserId();
        users.findByUserIdAndDeletedAtIsNull(actorId).orElseThrow(UserNotFoundException::new);

        AdditionalAttachmentExecution execution =
                executionRepository.findForUpdate(request.additionId())
                        .orElse(null);

        if (execution != null) {
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            if (execution.getProcessingMode() != ProcessingMode.V3
                    || !execution.getTripId().equals(tripId)
                    || !execution.acceptedBy(actorId)
                    || execution.getTotalAttachmentCount()
                    != request.totalAttachmentCount()) {
                throw new InvalidAttachmentUploadException();
            }

            if (execution.isTerminal()) {
                throw new TripAttachmentAddNotAllowedException();
            }
        } else {
            authorization.requireWritePermission(trip, actorId);
        }

        AdditionalAttachmentUploadBatch existing =
                uploadBatchRepository.findByAdditionIdAndBatchNo(
                        request.additionId(),
                        request.batchNo()
                ).orElse(null);

        if (existing != null) {
            requireSameRequest(
                    existing,
                    tripId,
                    ownerId,
                    request,
                    uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(
                            existing.getId()
                    )
            );

            if (execution == null) {
                throw new InvalidAttachmentUploadException();
            }

            if (existing.getStatus() != AdditionalAttachmentUploadStatus.PENDING) {
                throw new TripAttachmentAddNotAllowedException();
            }

            execution.recordUploadActivity(databaseTime());
            return existing;
        }

        List<AdditionalAttachmentUploadBatch> previousBatches =
                uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(
                        request.additionId()
                );

        if (previousBatches.isEmpty()) {
            if (request.batchNo() != 1) {
                throw new InvalidAttachmentUploadException();
            }

            long existingCount =
                    tripAttachmentRepository.countVisibleForEditByTripId(tripId);
            long pendingCount = executionRepository.countDeclaredPendingAttachments(tripId);

            if (existingCount + pendingCount + request.totalAttachmentCount() > 200) {
                throw new AttachmentUploadLimitExceededException();
            }
        } else {
            for (AdditionalAttachmentUploadBatch previous : previousBatches) {
                if (!previous.getTripId().equals(tripId)
                        || !previous.getUserId().equals(ownerId)
                        || !previous.getTotalAttachmentCount()
                        .equals(request.totalAttachmentCount())) {
                    throw new InvalidAttachmentUploadException();
                }
            }

            AdditionalAttachmentUploadBatch latest =
                    previousBatches.get(previousBatches.size() - 1);

            if (request.batchNo() != latest.getBatchNo() + 1
                    || latest.getLastBatch()
                    || !List.of(AdditionalAttachmentUploadStatus.VERIFIED,
                        AdditionalAttachmentUploadStatus.CONVERTING,
                        AdditionalAttachmentUploadStatus.PREPARED).contains(latest.getStatus())) {
                throw new InvalidAttachmentUploadException();
            }
        }

        List<AdditionalAttachmentUploadItem> previousItems =
                uploadItemRepository.findAdditionItems(request.additionId());

        int acceptedCount = previousItems.size() + request.attachments().size();

        if (acceptedCount > request.totalAttachmentCount()
                || request.complete()
                != (acceptedCount == request.totalAttachmentCount())) {
            throw new InvalidAttachmentUploadException();
        }

        long previousBytes = previousItems.stream()
                .mapToLong(AdditionalAttachmentUploadItem::getSizeBytes)
                .sum();

        if (previousBytes + batchBytes > MAX_ADDITION_BYTES) {
            throw new AttachmentUploadLimitExceededException();
        }

        LocalDateTime now = databaseTime();

        if (execution == null) {
            if (!previousBatches.isEmpty()) {
                throw new InvalidAttachmentUploadException();
            }

            conversionAdmission.assertNewExecutionAllowed();
            execution = executionRepository.save(
                    new AdditionalAttachmentExecution(
                            request.additionId(),
                            tripId,
                            ownerId,
                            request.totalAttachmentCount(),
                            now
                    )
            );
            execution.acceptV3(actorId);
            TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                    execution.getTripId(),
                    execution.getExecutionId(),
                    execution.getStatusVersion(),
                    execution.getDisplayStatus(),
                    execution.getCurrentStep(),
                    execution.getErrorCode(),
                    null, null, null, null, true
            );
            eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(
                    execution.getUserId(), snapshot
            ));
            logWorkflowStarted(tripId, execution.getExecutionId());
        }

        if (execution.isInputClosed()) {
            throw new TripAttachmentAddNotAllowedException();
        }

        userStatsService.reserveInitialUpload(ownerId, batchBytes);
        execution.reserve(batchBytes);
        execution.recordUploadActivity(now);

        AdditionalAttachmentUploadBatch savedBatch =
                uploadBatchRepository.save(
                        new AdditionalAttachmentUploadBatch(
                                UUID.randomUUID().toString(),
                                request.additionId(),
                                tripId,
                                ownerId,
                                request.batchNo(),
                                request.totalAttachmentCount(),
                                request.complete()
                        )
                );

        for (int index = 0; index < request.attachments().size(); index++) {
            AdditionalAttachmentUploadUrlRequest.Attachment attachment =
                    request.attachments().get(index);

            uploadItemRepository.save(
                    new AdditionalAttachmentUploadItem(
                            savedBatch,
                            index + 1,
                            attachment.fileName(),
                            attachment.contentType(),
                            attachment.sizeBytes(),
                            "trip-additions/" + request.additionId()
                                    + "/original/" + UUID.randomUUID()
                    )
            );
        }

        return savedBatch;
    }

    long validateRequest(AdditionalAttachmentUploadUrlRequest request) {
        if (request == null
                || request.additionId() == null
                || request.additionId().length() != 36
                || request.batchNo() == null
                || request.batchNo() < 1
                || request.totalAttachmentCount() == null
                || request.totalAttachmentCount() < 1
                || request.complete() == null
                || request.attachments() == null
                || request.attachments().isEmpty()) {
            throw new InvalidAttachmentUploadException();
        }

        try {
            UUID.fromString(request.additionId());
        } catch (IllegalArgumentException e) {
            throw new InvalidAttachmentUploadException();
        }

        if (request.totalAttachmentCount() > 200
                || request.attachments().size() > 10) {
            throw new AttachmentUploadLimitExceededException();
        }

        if (request.batchNo() > request.totalAttachmentCount()
                || request.attachments().size() > request.totalAttachmentCount()) {
            throw new InvalidAttachmentUploadException();
        }

        long batchBytes = 0;

        for (AdditionalAttachmentUploadUrlRequest.Attachment attachment
                : request.attachments()) {
            if (attachment == null) {
                throw new InvalidAttachmentUploadException();
            }

            batchBytes += AttachmentUploadValidation.validateFile(
                    attachment.fileName(),
                    attachment.contentType(),
                    attachment.sizeBytes()
            );
        }

        return batchBytes;
    }

    private LocalDateTime databaseTime() {
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP(6)",
                LocalDateTime.class
        );
    }

    private Trip requireCompletedTrip(Long tripId) {
        Trip trip = tripRepository.findActiveForUpdate(tripId).orElseThrow(TripNotFoundException::new);
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        if (storyGenerationJobRepository.existsByTripIdAndStatusIn(
                tripId,
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        )) {
            throw new TripAttachmentAddNotAllowedException();
        }
        return trip;
    }

    private void requireSameRequest(
            AdditionalAttachmentUploadBatch batch,
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadUrlRequest request,
            List<AdditionalAttachmentUploadItem> savedItems
    ) {
        if (!batch.getTripId().equals(tripId)
                || !batch.getUserId().equals(userId)
                || !batch.getAdditionId().equals(request.additionId())
                || !batch.getBatchNo().equals(request.batchNo())
                || !batch.getTotalAttachmentCount()
                .equals(request.totalAttachmentCount())
                || !batch.getLastBatch().equals(request.complete())
                || savedItems.size() != request.attachments().size()) {
            throw new InvalidAttachmentUploadException();
        }

        for (int index = 0; index < savedItems.size(); index++) {
            AdditionalAttachmentUploadItem saved = savedItems.get(index);
            AdditionalAttachmentUploadUrlRequest.Attachment requested =
                    request.attachments().get(index);

            if (!saved.getOriginalFileName().equals(requested.fileName())
                    || !saved.getContentType().equals(requested.contentType())
                    || !saved.getSizeBytes().equals(requested.sizeBytes())) {
                throw new InvalidAttachmentUploadException();
            }
        }
    }

    private void logWorkflowStarted(Long tripId, String executionId) {
        WorkflowLog.event("trip_reanalysis", tripId, executionId, "workflow_started");
    }
}
