package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class InitialAttachmentUploadUrlServiceTest {
    private static final long MIB = 1024L * 1024;
    private final InitialAttachmentUploadItemRepository items = mock(InitialAttachmentUploadItemRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final InitialAttachmentUploadUrlService service = new InitialAttachmentUploadUrlService(
            mock(InitialAttachmentUploadBatchRepository.class),
            items,
            mock(TripRepository.class),
            mock(TripAttachmentRepository.class),
            storage);

    @Test
    void 필수_헤더와_10분_유효기간이_있는_파일_URL을_순서대로_발급한다() {
        var request = request(2, true, List.of(
                file("same.jpg", "image/jpeg", 1024),
                file("same.jpg", "image/png", 2048)));
        var batch = mock(InitialAttachmentUploadBatch.class);
        when(batch.getId()).thenReturn(7L);
        when(batch.getUploadId()).thenReturn("upload-id");
        var subject = spy(service);
        doReturn(batch).when(subject).prepareBatch(10L, 2L, request);
        var savedItems = List.of(
                savedItem("same.jpg", "image/jpeg", "key-one"),
                savedItem("same.jpg", "image/png", "key-two"));
        when(items.findAllByBatch_IdOrderByFileOrderAsc(7L)).thenReturn(savedItems);
        when(storage.createUploadUrl("key-one", "image/jpeg", Duration.ofMinutes(10)))
                .thenReturn("https://example.test/one");
        when(storage.createUploadUrl("key-two", "image/png", Duration.ofMinutes(10)))
                .thenReturn("https://example.test/two");

        OffsetDateTime earliestExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        var response = subject.issueUploadUrls(10L, 2L, request);
        OffsetDateTime latestExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);

        assertThat(response.uploadId()).isEqualTo("upload-id");
        assertThat(response.attachments()).extracting(a -> a.uploadUrl())
                .containsExactly("https://example.test/one", "https://example.test/two");
        assertThat(response.attachments()).allSatisfy(a -> {
            assertThat(a.fileName()).isEqualTo("same.jpg");
            assertThat(a.method()).isEqualTo("PUT");
            assertThat(a.expiresAt()).isBetween(earliestExpiry, latestExpiry);
        });
        assertThat(response.attachments().get(0).headers())
                .isEqualTo(Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"));
        assertThat(response.attachments().get(1).headers())
                .isEqualTo(Map.of("Content-Type", "image/png", "If-None-Match", "*"));

        subject.issueUploadUrls(10L, 2L, request);
        verify(storage, times(2)).createUploadUrl("key-one", "image/jpeg", Duration.ofMinutes(10));
        verify(storage, times(2)).createUploadUrl("key-two", "image/png", Duration.ofMinutes(10));
    }

    private InitialAttachmentUploadItem savedItem(String name, String type, String key) {
        var item = mock(InitialAttachmentUploadItem.class);
        when(item.getOriginalFileName()).thenReturn(name);
        when(item.getContentType()).thenReturn(type);
        when(item.getObjectKey()).thenReturn(key);
        return item;
    }

    @Test
    void 허용된_메타데이터를_받고_파일을_읽지_않은_채_전체_바이트를_반환한다() {
        InitialAttachmentUploadUrlRequest request = request(15, false, List.of(
                file("same.jpg", "image/jpeg", 1024),
                file("same.jpg", "image/png", 2048),
                file("photo.heic", "image/heic", 4096)));

        assertThat(service.validateRequest(request)).isEqualTo(7168L);
    }

    @Test
    void 필수_요청_필드_누락은_거부하고_완료_여부_false는_허용한다() {
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
    void 파일이_10개를_넘거나_선언한_전체_개수를_넘으면_거부한다() {
        var photo = file("photo.jpg", "image/jpeg", 1024);

        assertThatThrownBy(() -> service.validateRequest(request(15, false, Collections.nCopies(11, photo))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThatThrownBy(() -> service.validateRequest(request(1, true, List.of(photo, photo))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void 파일_크기_제한값은_허용하고_1바이트_초과는_거부한다() {
        assertThat(service.validateRequest(request(1, true, List.of(file("photo.jpg", "image/jpeg", 15 * MIB)))))
                .isEqualTo(15 * MIB);
        assertThatThrownBy(() -> service.validateRequest(request(1, true,
                List.of(file("photo.jpg", "image/jpeg", 15 * MIB + 1)))))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
    }

    @Test
    void 배치_크기_제한값은_허용하고_1바이트_초과는_거부한다() {
        List<InitialAttachmentUploadUrlRequest.Attachment> files = new ArrayList<>(
                Collections.nCopies(9, file("photo.jpg", "image/jpeg", 15 * MIB)));
        files.add(file("last.jpg", "image/jpeg", 10 * MIB));

        assertThat(service.validateRequest(request(10, true, files))).isEqualTo(145 * MIB);

        files.set(9, file("last.jpg", "image/jpeg", 10 * MIB + 1));
        assertThatThrownBy(() -> service.validateRequest(request(10, true, files)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
    }

    @Test
    void 불완전한_파일_메타데이터를_거부한다() {
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
    void 지원하지_않는_선언된_콘텐츠_타입을_거부한다() {
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
