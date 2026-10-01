package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import lombok.RequiredArgsConstructor;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadUrlService {

    private final InitialAttachmentUploadBatchRepository batches;
    private final InitialAttachmentUploadItemRepository items;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;

    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 145L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/heic");

    @Transactional
    public InitialAttachmentUploadBatch prepareBatch(
            Long tripId,
            Long userId,
            InitialAttachmentUploadUrlRequest request
    ) {
        long batchBytes = validateRequest(request);

        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        InitialAttachmentUploadBatch latest =
                batches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId)
                        .orElse(null);

        String executionId;

        if (latest == null) {
            if (request.batchNo() != 1
                    || !attachments.findAllByTripIdAndDeletedAtIsNull(tripId).isEmpty()) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }

            executionId = UUID.randomUUID().toString();
        } else {
            InitialAttachmentUploadBatch existing =
                    batches.findByExecutionIdAndBatchNo(
                            latest.getExecutionId(),
                            request.batchNo()
                    ).orElse(null);

            if (existing != null) {
                requireSameRequest(existing, request);

                if (existing.getStatus() != InitialAttachmentUploadStatus.PENDING) {
                    throw new TripInitialAttachmentUploadNotAllowedException();
                }

                return existing;
            }

            if (latest.getStatus() != InitialAttachmentUploadStatus.COMPLETED
                    || latest.getLastBatch()
                    || request.batchNo() != latest.getBatchNo() + 1
                    || !latest.getTotalAttachmentCount()
                    .equals(request.totalAttachmentCount())) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }

            executionId = latest.getExecutionId();
        }

        List<InitialAttachmentUploadItem> previousItems =
                latest == null
                        ? List.of()
                        : items.findAllByBatch_ExecutionId(executionId);

        int attachmentCount =
                previousItems.size() + request.attachments().size();

        if (attachmentCount > request.totalAttachmentCount()
                || request.complete()
                != (attachmentCount == request.totalAttachmentCount())) {
            throw new InvalidAttachmentUploadException();
        }

        long totalBytes = previousItems.stream()
                .mapToLong(InitialAttachmentUploadItem::getSizeBytes)
                .sum() + batchBytes;

        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new AttachmentUploadLimitExceededException();
        }

        return saveNewBatch(tripId, userId, executionId, request);
    }

    private InitialAttachmentUploadBatch saveNewBatch(
            Long tripId,
            Long userId,
            String executionId,
            InitialAttachmentUploadUrlRequest request
    ) {
        InitialAttachmentUploadBatch batch = batches.save(
                new InitialAttachmentUploadBatch(
                        UUID.randomUUID().toString(),
                        executionId,
                        tripId,
                        userId,
                        request.batchNo(),
                        request.totalAttachmentCount(),
                        request.complete()
                )
        );

        List<InitialAttachmentUploadItem> uploadItems = new ArrayList<>();

        for (int i = 0; i < request.attachments().size(); i++) {
            InitialAttachmentUploadUrlRequest.Attachment attachment =
                    request.attachments().get(i);

            String objectKey = "trip-uploads/" + executionId
                    + "/original/" + UUID.randomUUID();

            uploadItems.add(new InitialAttachmentUploadItem(
                    batch,
                    i + 1,
                    attachment.fileName(),
                    attachment.contentType(),
                    attachment.sizeBytes(),
                    objectKey
            ));
        }

        items.saveAll(uploadItems);
        return batch;
    }

    private void requireSameRequest(
            InitialAttachmentUploadBatch batch,
            InitialAttachmentUploadUrlRequest request
    ) {
        List<InitialAttachmentUploadItem> savedItems =
                items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());

        if (!batch.getTotalAttachmentCount().equals(request.totalAttachmentCount())
                || !batch.getLastBatch().equals(request.complete())
                || savedItems.size() != request.attachments().size()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        for (int i = 0; i < savedItems.size(); i++) {
            InitialAttachmentUploadItem saved = savedItems.get(i);
            InitialAttachmentUploadUrlRequest.Attachment requested =
                    request.attachments().get(i);

            if (!saved.getOriginalFileName().equals(requested.fileName())
                    || !saved.getContentType().equals(requested.contentType())
                    || !saved.getSizeBytes().equals(requested.sizeBytes())) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }
        }
    }
    long validateRequest(InitialAttachmentUploadUrlRequest request) {
        if (request == null
                || request.batchNo() == null
                || request.batchNo() < 1
                || request.totalAttachmentCount() == null
                || request.totalAttachmentCount() < 1
                || request.totalAttachmentCount() > 200
                || request.complete() == null
                || request.attachments() == null
                || request.attachments().isEmpty()) {
            throw new InvalidAttachmentUploadException();
        }

        if (request.attachments().size() > 10) {
            throw new AttachmentUploadLimitExceededException();
        }

        if (request.attachments().size() > request.totalAttachmentCount()) {
            throw new InvalidAttachmentUploadException();
        }

        long batchBytes = 0;

        for (InitialAttachmentUploadUrlRequest.Attachment attachment
                : request.attachments()) {
            if (attachment == null
                    || attachment.fileName() == null
                    || attachment.fileName().isBlank()
                    || attachment.fileName().length() > 255
                    || attachment.contentType() == null
                    || attachment.contentType().isBlank()
                    || attachment.contentType().length() > 100
                    || attachment.sizeBytes() == null
                    || attachment.sizeBytes() < 1) {
                throw new InvalidAttachmentUploadException();
            }

            if (!ALLOWED_CONTENT_TYPES.contains(attachment.contentType())) {
                throw new UnsupportedAttachmentFormatException();
            }

            if (attachment.sizeBytes() > MAX_FILE_BYTES) {
                throw new AttachmentUploadLimitExceededException();
            }

            batchBytes += attachment.sizeBytes();

            if (batchBytes > MAX_BATCH_BYTES) {
                throw new AttachmentUploadLimitExceededException();
            }
        }

        return batchBytes;
    }
}