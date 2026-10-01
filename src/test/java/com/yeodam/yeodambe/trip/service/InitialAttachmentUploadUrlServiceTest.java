package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InitialAttachmentUploadUrlServiceTest {
    private static final long MIB = 1024L * 1024;
    private final InitialAttachmentUploadUrlService service = new InitialAttachmentUploadUrlService(
            mock(InitialAttachmentUploadBatchRepository.class),
            mock(InitialAttachmentUploadItemRepository.class),
            mock(TripRepository.class),
            mock(TripAttachmentRepository.class));

    @Test
    void acceptsAllowedMetadataAndReturnsTotalBytesWithoutReadingFiles() {
        InitialAttachmentUploadUrlRequest request = request(15, false, List.of(
                file("same.jpg", "image/jpeg", 1024),
                file("same.jpg", "image/png", 2048),
                file("photo.heic", "image/heic", 4096)));

        assertThat(service.validateRequest(request)).isEqualTo(7168L);
    }

    @Test
    void rejectsMissingRequiredRequestFieldsButAllowsFalseComplete() {
        List<InitialAttachmentUploadUrlRequest.Attachment> files = List.of(file("photo.jpg", "image/jpeg", 1024));

        assertThatThrownBy(() -> service.validateRequest(null))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(new InitialAttachmentUploadUrlRequest(1, 15, null, files)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(new InitialAttachmentUploadUrlRequest(null, 15, false, files)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(request(15, false, List.of())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.validateRequest(request(201, false, files)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void rejectsMoreThanTenFilesOrMoreFilesThanDeclaredTotal() {
        var photo = file("photo.jpg", "image/jpeg", 1024);

        assertThatThrownBy(() -> service.validateRequest(request(15, false, Collections.nCopies(11, photo))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1, true, List.of(photo, photo))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void acceptsExactFileLimitAndRejectsOneExtraByte() {
        assertThat(service.validateRequest(request(1, true, List.of(file("photo.jpg", "image/jpeg", 15 * MIB)))))
                .isEqualTo(15 * MIB);
        assertThatThrownBy(() -> service.validateRequest(request(1, true,
                List.of(file("photo.jpg", "image/jpeg", 15 * MIB + 1)))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
    }

    @Test
    void acceptsExactBatchLimitAndRejectsOneExtraByte() {
        List<InitialAttachmentUploadUrlRequest.Attachment> files = new ArrayList<>(
                Collections.nCopies(9, file("photo.jpg", "image/jpeg", 15 * MIB)));
        files.add(file("last.jpg", "image/jpeg", 10 * MIB));

        assertThat(service.validateRequest(request(10, true, files))).isEqualTo(145 * MIB);

        files.set(9, file("last.jpg", "image/jpeg", 10 * MIB + 1));
        assertThatThrownBy(() -> service.validateRequest(request(10, true, files)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
    }

    @Test
    void rejectsIncompleteFileMetadata() {
        List<InitialAttachmentUploadUrlRequest.Attachment> invalidFiles = Arrays.asList(
                null,
                file(" ", "image/jpeg", 1024),
                file("a".repeat(256), "image/jpeg", 1024),
                file("photo.jpg", null, 1024),
                file("photo.jpg", "image/jpeg", 0),
                new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", null));

        for (InitialAttachmentUploadUrlRequest.Attachment invalid : invalidFiles) {
            assertThatThrownBy(() -> service.validateRequest(request(1, true, Collections.singletonList(invalid))))
                    .isInstanceOf(InvalidAttachmentUploadException.class);
        }
    }

    @Test
    void rejectsUnsupportedDeclaredContentType() {
        assertThatThrownBy(() -> service.validateRequest(request(1, true,
                List.of(file("photo.gif", "image/gif", 1024)))))
                .isInstanceOf(UnsupportedAttachmentFormatException.class);
    }

    private InitialAttachmentUploadUrlRequest request(
            int total, boolean complete, List<InitialAttachmentUploadUrlRequest.Attachment> files) {
        return new InitialAttachmentUploadUrlRequest(1, total, complete, files);
    }

    private InitialAttachmentUploadUrlRequest.Attachment file(String name, String contentType, long bytes) {
        return new InitialAttachmentUploadUrlRequest.Attachment(name, contentType, bytes);
    }
}
