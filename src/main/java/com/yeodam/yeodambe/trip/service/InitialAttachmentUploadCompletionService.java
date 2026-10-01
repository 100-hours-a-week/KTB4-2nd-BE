package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadCompletionService {

    private final TripAttachmentStorageClient storage;
    private final TripAttachmentDerivativeService derivatives;

    void verifyUploadedFiles(
            List<InitialAttachmentUploadItem> uploadItems
    ) {
        for (InitialAttachmentUploadItem item : uploadItems) {
            long actualBytes;

            try {
                actualBytes = storage.size(item.getObjectKey());
            } catch (S3Exception failure) {
                String errorCode = failure.awsErrorDetails() == null
                        ? null : failure.awsErrorDetails().errorCode();

                if (failure.statusCode() == 404 && !"NoSuchBucket".equals(errorCode)) {
                    throw new InvalidAttachmentUploadException();
                }

                throw new AttachmentStorageException(
                        item.getObjectKey(),
                        failure
                );
            }

            if (actualBytes != item.getSizeBytes()) {
                throw new InvalidAttachmentUploadException();
            }

            verifyFileType(item);
        }
    }

    List<DerivedPhotoKeys> createDerived(
            String executionId,
            List<InitialAttachmentUploadItem> uploadItems
    ) {
        List<String> originalKeys = uploadItems.stream()
                .map(InitialAttachmentUploadItem::getObjectKey)
                .toList();

        List<String> mimeTypes = uploadItems.stream()
                .map(InitialAttachmentUploadItem::getContentType)
                .toList();

        List<DerivedPhotoKeys> derived =
                derivatives.createAll(executionId, originalKeys, mimeTypes)
                        .join();

        if (derived == null || derived.size() != uploadItems.size()) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.DERIVED_ATTACHMENT_COUNT_MISMATCH.message()
            );
        }

        for (int i = 0; i < derived.size(); i++) {
            DerivedPhotoKeys photo = derived.get(i);

            if (photo == null
                    || !originalKeys.get(i).equals(photo.originalKey())
                    || photo.analyzeKey() == null || photo.analyzeKey().isBlank()
                    || photo.previewKey() == null || photo.previewKey().isBlank()
                    || ("image/heic".equals(mimeTypes.get(i))
                    && (photo.displayKey() == null || photo.displayKey().isBlank()))) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.DERIVED_ATTACHMENT_RESULT_INVALID.message()
                );
            }
        }

        return derived;
    }

    void retainFiles(List<DerivedPhotoKeys> derived) {
        Set<String> objectKeys = new LinkedHashSet<>();

        for (DerivedPhotoKeys photo : derived) {
            objectKeys.add(photo.originalKey());
            objectKeys.add(photo.analyzeKey());
            objectKeys.add(photo.previewKey());

            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                objectKeys.add(photo.displayKey());
            }
        }

        storage.retain(List.copyOf(objectKeys));
    }

    boolean cleanupDerived(
            List<DerivedPhotoKeys> derived,
            RuntimeException failure
    ) {
        Set<String> originalKeys = new LinkedHashSet<>();
        Set<String> derivedKeys = new LinkedHashSet<>();

        for (DerivedPhotoKeys photo : derived) {
            originalKeys.add(photo.originalKey());
            derivedKeys.add(photo.analyzeKey());
            derivedKeys.add(photo.previewKey());

            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                derivedKeys.add(photo.displayKey());
            }
        }

        derivedKeys.removeAll(originalKeys);

        boolean cleaned = true;

        for (String key : derivedKeys) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                cleaned = false;
            }
        }

        return cleaned;
    }

    private void verifyFileType(InitialAttachmentUploadItem item) {
        byte[] header;

        try (InputStream input = storage.open(item.getObjectKey())) {
            header = input.readNBytes(12);
        } catch (IOException | SdkException failure) {
            throw new AttachmentStorageException(
                    item.getObjectKey(),
                    failure
            );
        }

        String detectedType = detectType(header);

        if (!detectedType.equals(item.getContentType())) {
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
}
