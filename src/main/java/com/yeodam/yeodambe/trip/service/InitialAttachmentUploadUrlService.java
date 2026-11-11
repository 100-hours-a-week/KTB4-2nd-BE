package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadUrlResponse;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadUrlService {
    private static final long MAX_BATCH_BYTES = 145L * 1024 * 1024;
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    private final InitialAttachmentUploadTransactionService transactions;
    private final TripAttachmentStorageClient storage;
    private final TripMutationCoordinator mutations;
    private final TripAccessService access;

    public InitialAttachmentUploadUrlResponse issueUploadUrls(
            Long tripId,
            Long userId,
            InitialAttachmentUploadUrlRequest request
    ) {
        validateRequest(request);
        access.requireOwnedTrip(tripId, userId);
        InitialAttachmentUploadBatchSnapshot batch = mutations.execute(
                tripId, () -> transactions.prepareBatch(tripId, userId, request)
        );
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(UPLOAD_URL_TTL);
        List<InitialAttachmentUploadUrlResponse.Attachment> responseItems = batch.photos().stream()
                .map(item -> new InitialAttachmentUploadUrlResponse.Attachment(
                        item.fileName(),
                        storage.createUploadUrl(item.objectKey(), item.contentType(), UPLOAD_URL_TTL),
                        "PUT",
                        Map.of("Content-Type", item.contentType(), "If-None-Match", "*"),
                        expiresAt
                ))
                .toList();
        return new InitialAttachmentUploadUrlResponse(batch.uploadId(), responseItems);
    }

    static long validateRequest(InitialAttachmentUploadUrlRequest request) {
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

        if (request.batchNo() > request.totalAttachmentCount()
                || request.attachments().size() > request.totalAttachmentCount()) {
            throw new InvalidAttachmentUploadException();
        }

        long batchBytes = 0;

        for (InitialAttachmentUploadUrlRequest.Attachment attachment
                : request.attachments()) {
            if (attachment == null) {
                throw new InvalidAttachmentUploadException();
            }

            batchBytes += AttachmentUploadValidation.validateFile(
                    attachment.fileName(),
                    attachment.contentType(),
                    attachment.sizeBytes()
            );

            if (batchBytes > MAX_BATCH_BYTES) {
                throw new AttachmentUploadLimitExceededException();
            }
        }

        return batchBytes;
    }
}
