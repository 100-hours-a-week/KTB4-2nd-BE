package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution.ProcessingMode;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadUrlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentUploadUrlService {
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    private final AdditionalAttachmentAdmissionTransactionService transactions;
    private final AdditionalAttachmentUploadItemRepository uploadItemRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final TripMutationCoordinator mutations;

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
                    item.getSizeBytes(),
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

    public AdditionalAttachmentUploadBatch prepareBatch(
            Long tripId,
            Long actorId,
            AdditionalAttachmentUploadUrlRequest request
    ) {
        ProcessingMode mode = transactions.validateAccess(tripId, actorId, request);
        return mutations.execute(tripId, () -> mode == ProcessingMode.LEGACY
                ? transactions.continueLegacyBatch(tripId, actorId, request)
                : transactions.prepareBatch(tripId, actorId, request));
    }
}
