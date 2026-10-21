package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import lombok.RequiredArgsConstructor;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadUrlResponse;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentUploadUrlService {

    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadItemRepository uploadItemRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final AdditionalAttachmentExecutionRepository executionRepository;
    private final UserStatsService userStatsService;
    private final JdbcTemplate jdbcTemplate;
    private final StoryGenerationJobRepository storyGenerationJobRepository;

    private static final long MAX_ADDITION_BYTES = 3L * 1024 * 1024 * 1024;
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    @Transactional
    public AdditionalAttachmentUploadUrlResponse issueUploadUrls(
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadUrlRequest request
    ) {
        AdditionalAttachmentUploadBatch batch =
                prepareBatch(tripId, userId, request);

        if (batch.getStatus() != AdditionalAttachmentUploadStatus.PENDING) {
            throw new TripAttachmentAddNotAllowedException();
        }

        List<AdditionalAttachmentUploadItem> uploadItems =
                uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(
                        batch.getId()
                );

        OffsetDateTime expiresAt =
                OffsetDateTime.now(ZoneOffset.UTC).plus(UPLOAD_URL_TTL);

        List<AdditionalAttachmentUploadUrlResponse.Attachment> responseItems =
                new ArrayList<>();

        for (AdditionalAttachmentUploadItem item : uploadItems) {
            String uploadUrl = tripAttachmentStorageClient.createUploadUrl(
                    item.getObjectKey(),
                    item.getContentType(),
                    UPLOAD_URL_TTL
            );

            responseItems.add(
                    new AdditionalAttachmentUploadUrlResponse.Attachment(
                            item.getOriginalFileName(),
                            uploadUrl,
                            "PUT",
                            Map.of(
                                    "Content-Type", item.getContentType(),
                                    "If-None-Match", "*"
                            ),
                            expiresAt
                    )
            );
        }

        return new AdditionalAttachmentUploadUrlResponse(
                batch.getUploadId(),
                responseItems
        );
    }

    @Transactional
    public AdditionalAttachmentUploadBatch prepareBatch(
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadUrlRequest request
    ) {
        long batchBytes = validateRequest(request);
        requireOwnedCompletedTrip(tripId, userId);

        AdditionalAttachmentExecution execution =
                executionRepository.findForUpdate(request.additionId())
                        .orElse(null);

        if (execution != null) {
            if (!execution.getTripId().equals(tripId)
                    || !execution.getUserId().equals(userId)
                    || execution.getTotalAttachmentCount()
                    != request.totalAttachmentCount()) {
                throw new InvalidAttachmentUploadException();
            }

            if (execution.isTerminal()) {
                throw new TripAttachmentAddNotAllowedException();
            }
        } else if (executionRepository.existsByTripIdAndEndedAtIsNull(tripId)) {
            throw new TripAttachmentAddNotAllowedException();
        }

        boolean otherAdditionExists =
                uploadBatchRepository.existsByTripIdAndAdditionIdNotAndStatusIn(
                        tripId,
                        request.additionId(),
                        List.of(
                                AdditionalAttachmentUploadStatus.PENDING,
                                AdditionalAttachmentUploadStatus.VERIFIED,
                                AdditionalAttachmentUploadStatus.CONVERTING,
                                AdditionalAttachmentUploadStatus.PREPARED,
                                AdditionalAttachmentUploadStatus.QUEUED,
                                AdditionalAttachmentUploadStatus.PROCESSING
                        )
                );

        if (otherAdditionExists) {
            throw new TripAttachmentAddNotAllowedException();
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
                    userId,
                    request,
                    uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(
                            existing.getId()
                    )
            );

            if (execution == null) {
                throw new InvalidAttachmentUploadException();
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
                    tripAttachmentRepository.countForEditByTripId(tripId);

            if (existingCount + request.totalAttachmentCount() > 200) {
                throw new AttachmentUploadLimitExceededException();
            }
        } else {
            for (AdditionalAttachmentUploadBatch previous : previousBatches) {
                if (!previous.getTripId().equals(tripId)
                        || !previous.getUserId().equals(userId)
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

            execution = executionRepository.save(
                    new AdditionalAttachmentExecution(
                            request.additionId(),
                            tripId,
                            userId,
                            request.totalAttachmentCount(),
                            now
                    )
            );
            logWorkflowStarted(tripId, execution.getExecutionId());
        }

        if (execution.isInputClosed()) {
            throw new TripAttachmentAddNotAllowedException();
        }

        userStatsService.reserveInitialUpload(userId, batchBytes);
        execution.reserve(batchBytes);
        execution.recordUploadActivity(now);

        AdditionalAttachmentUploadBatch savedBatch =
                uploadBatchRepository.save(
                        new AdditionalAttachmentUploadBatch(
                                UUID.randomUUID().toString(),
                                request.additionId(),
                                tripId,
                                userId,
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

    private Trip requireOwnedCompletedTrip(Long tripId, Long userId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

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
