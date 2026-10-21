package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadCompletionService {
    private final TripAttachmentStorageClient storage;
    private final InitialAttachmentUploadTransactionService transactions;
    private final TripAttachmentDerivativeService derivatives;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TripProcessingStatusResponse complete(
            Long tripId,
            Long userId,
            InitialAttachmentUploadCompleteRequest request
    ) {
        if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
            throw new InvalidAttachmentUploadException();
        }

        var candidate = transactions.loadAcceptanceCandidate(tripId, userId, request.uploadId());

        TripProcessingStatusResponse status;
        try (var scope = WorkflowMdcScope.job("trip_creation", tripId, candidate.executionId());
             var timing = startUploadAcceptanceLogging(candidate.batchNo())) {
            if (candidate.status() == InitialAttachmentUploadStatus.PENDING) {
                verifyUploadedFiles(candidate.photos());
            }
            status = transactions.startProcessing(tripId, userId, request.uploadId());
            timing.success();
        }
        if (status.status() == TripProcessingStatusResponse.Status.PROCESSING) {
            derivatives.processAcceptedBatch(tripId, userId, request.uploadId());
        }
        return status;
    }

    void verifyUploadedFiles(
            List<InitialAttachmentUploadBatchSnapshot.Photo> uploadItems
    ) {
        for (InitialAttachmentUploadBatchSnapshot.Photo item : uploadItems) {
            long actualBytes;

            try {
                actualBytes = storage.size(item.objectKey());
            } catch (S3Exception failure) {
                String errorCode = failure.awsErrorDetails() == null
                        ? null : failure.awsErrorDetails().errorCode();

                if (failure.statusCode() == 404 && !"NoSuchBucket".equals(errorCode)) {
                    throw new InvalidAttachmentUploadException();
                }

                throw new AttachmentStorageException(
                        item.objectKey(),
                        failure
                );
            } catch (SdkException failure) {
                throw new AttachmentStorageException(item.objectKey(), failure);
            }

            if (actualBytes != item.sizeBytes()) {
                throw new InvalidAttachmentUploadException();
            }

            verifyFileType(item);
        }
    }

    private void verifyFileType(InitialAttachmentUploadBatchSnapshot.Photo item) {
        byte[] header;

        try (InputStream input = storage.openHeader(item.objectKey())) {
            header = input.readNBytes(12);
        } catch (IOException | SdkException failure) {
            throw new AttachmentStorageException(
                    item.objectKey(),
                    failure
            );
        }

        String detectedType = detectType(header);

        if (!detectedType.equals(item.contentType())) {
            throw new InvalidAttachmentUploadException();
        }
    }

    private String detectType(byte[] header) {
        if (header.length >= 3
                && (header[0] & 255) == 255
                && (header[1] & 255) == 216
                && (header[2] & 255) == 255) {
            return "image/jpeg";
        }

        byte[] png = {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
        };

        if (header.length >= 8
                && Arrays.equals(header, 0, 8, png, 0, 8)) {
            return "image/png";
        }

        if (header.length >= 12
                && Arrays.equals(
                        header, 4, 8,
                        "ftyp".getBytes(StandardCharsets.US_ASCII), 0, 4
                )
                && Set.of("heic", "heix", "heim", "heis")
                        .contains(new String(
                                header, 8, 4, StandardCharsets.US_ASCII
                        ))) {
            return "image/heic";
        }

        throw new UnsupportedAttachmentFormatException();
    }

    private WorkflowLog.Stage startUploadAcceptanceLogging(int batchNo) {
        return WorkflowLog.stage("original_upload_acceptance").field("batch_no", batchNo);
    }
}
