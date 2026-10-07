package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdditionalAttachmentUploadUrlServiceTest {
    private static final String ADDITION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final long MIB = 1024L * 1024;
    private final AdditionalAttachmentUploadUrlService service =
            new AdditionalAttachmentUploadUrlService(
                    mock(TripRepository.class),
                    mock(AdditionalAttachmentUploadBatchRepository.class),
                    mock(AdditionalAttachmentUploadItemRepository.class),
                    mock(TripAttachmentRepository.class),
                    mock(TripAttachmentStorageClient.class));

    @Test
    void acceptsSupportedFilesAndReturnsBatchBytes() {
        var request = request(200, List.of(
                file("photo.jpg", "image/jpeg", 1024),
                file("photo.png", "image/png", 2048),
                file("photo.heic", "image/heic", 4096)));

        assertThat(service.validateRequest(request)).isEqualTo(7168);
    }

    @Test
    void rejectsMissingRequestAndInvalidAdditionId() {
        assertThatThrownBy(() -> service.validateRequest(null))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        var files = List.of(file("photo.jpg", "image/jpeg", 1024));
        for (String id : new String[]{null, "invalid", "x".repeat(36)}) {
            assertThatThrownBy(() -> service.validateRequest(
                    new AdditionalAttachmentUploadUrlRequest(id, 1, 1, true, files)))
                    .isInstanceOf(InvalidAttachmentUploadException.class);
        }
        assertThatThrownBy(() -> service.validateRequest(
                new AdditionalAttachmentUploadUrlRequest(ADDITION_ID, 1, 1, null, files)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void acceptsTenFilesAndRejectsElevenOrTotalAboveTwoHundred() {
        var photo = file("photo.jpg", "image/jpeg", 15 * MIB);
        assertThat(service.validateRequest(request(200, Collections.nCopies(10, photo))))
                .isEqualTo(150 * MIB);
        assertThatThrownBy(() -> service.validateRequest(request(200, Collections.nCopies(11, photo))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThatThrownBy(() -> service.validateRequest(request(201, List.of(photo))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
    }

    @Test
    void rejectsBatchNumberAndFileCountInconsistentWithTotal() {
        var photo = file("photo.jpg", "image/jpeg", 1024);
        assertThatThrownBy(() -> service.validateRequest(
                new AdditionalAttachmentUploadUrlRequest(ADDITION_ID, 2, 1, true, List.of(photo))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1, List.of(photo, photo))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void rejectsEmptyFilesAndMissingMetadata() {
        assertThatThrownBy(() -> service.validateRequest(request(1, List.of())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1, Collections.singletonList(null))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1,
                List.of(file(" ", "image/jpeg", 1024)))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void acceptsExactFileLimitAndRejectsExtraByteOrUnsupportedFormat() {
        assertThat(service.validateRequest(request(1, List.of(file("photo.jpg", "image/jpeg", 15 * MIB)))))
                .isEqualTo(15 * MIB);
        assertThatThrownBy(() -> service.validateRequest(request(1,
                List.of(file("photo.jpg", "image/jpeg", 15 * MIB + 1)))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1,
                List.of(file("photo.gif", "image/gif", 1024)))))
                .isInstanceOf(UnsupportedAttachmentFormatException.class);
    }

    private AdditionalAttachmentUploadUrlRequest request(
            int total, List<AdditionalAttachmentUploadUrlRequest.Attachment> files) {
        return new AdditionalAttachmentUploadUrlRequest(ADDITION_ID, 1, total, false, files);
    }

    private AdditionalAttachmentUploadUrlRequest.Attachment file(String name, String type, long bytes) {
        return new AdditionalAttachmentUploadUrlRequest.Attachment(name, type, bytes);
    }
}
