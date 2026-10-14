package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;

import java.util.Set;

public final class AttachmentUploadValidation {

    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/heic");

    private AttachmentUploadValidation() {
    }

    public static long validateFile(
            String fileName,
            String contentType,
            Long sizeBytes
    ) {
        if (fileName == null
                || fileName.isBlank()
                || fileName.length() > 255
                || contentType == null
                || contentType.isBlank()
                || contentType.length() > 100
                || sizeBytes == null
                || sizeBytes < 1) {
            throw new InvalidAttachmentUploadException();
        }

        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedAttachmentFormatException();
        }

        if (sizeBytes > MAX_FILE_BYTES) {
            throw new AttachmentUploadLimitExceededException();
        }

        return sizeBytes;
    }
}