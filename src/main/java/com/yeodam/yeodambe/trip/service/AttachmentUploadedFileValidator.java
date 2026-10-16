package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentUploadedFileValidator {

    private final TripAttachmentStorageClient tripAttachmentStorageClient;

    public void verifySize(String objectKey, long expectedBytes) {
        long actualBytes;

        try {
            actualBytes = tripAttachmentStorageClient.size(objectKey);
        } catch (S3Exception failure) {
            String errorCode = failure.awsErrorDetails() == null
                    ? null
                    : failure.awsErrorDetails().errorCode();

            if (failure.statusCode() == 404
                    && !"NoSuchBucket".equals(errorCode)) {
                throw new InvalidAttachmentUploadException();
            }

            throw new AttachmentStorageException(objectKey, failure);
        }

        if (actualBytes != expectedBytes) {
            throw new InvalidAttachmentUploadException();
        }
    }

    public void verifyType(String objectKey, String expectedContentType) {
        byte[] header;

        try (InputStream input = tripAttachmentStorageClient.open(objectKey)) {
            header = input.readNBytes(12);
        } catch (IOException | SdkException failure) {
            throw new AttachmentStorageException(objectKey, failure);
        }

        String detectedType = detectType(header);

        if (!detectedType.equals(expectedContentType)) {
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