package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.ratelimit.RequestRateLimitExceededException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentDownloadResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkAttachmentDownloadConcurrencyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실행_중에는_추가_다운로드를_거절하고_성공과_실패_후_슬롯을_반환한다(boolean fail) throws Exception {
        TripAttachmentRepository repository = mock(TripAttachmentRepository.class);
        TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
        BulkAttachmentDownloadService service = new BulkAttachmentDownloadService(
                repository, storage, mock(TripEditAuthorizationService.class), 1);
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);
        when(attachment.getTripId()).thenReturn(10L);
        when(attachment.getTrip()).thenReturn(mock(Trip.class));
        when(attachment.getFile()).thenReturn(file);
        when(attachment.getId()).thenReturn(1L);
        when(file.getObjectKey()).thenReturn("key");
        when(file.getOriginalFileName()).thenReturn("photo.jpg");
        when(repository.findAllActiveForDownloadByIds(List.of(1L))).thenReturn(List.of(attachment));
        when(storage.storeDownloadArchive(any(Path.class))).thenReturn("archive");
        when(storage.createDownloadUrl("archive", "yeodam-attachments.zip")).thenReturn("https://download.test");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(storage.open("key")).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                if (fail) {
                    throw new AttachmentStorageException("key", new IOException());
                }
            }
            return new ByteArrayInputStream(new byte[]{1});
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                Future<BulkAttachmentDownloadResponse> running = executor.submit(
                        () -> service.issueDownloadUrl(2L, List.of(1L)));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.issueDownloadUrl(3L, List.of(1L)))
                        .isInstanceOf(RequestRateLimitExceededException.class);
                verify(storage, times(1)).open("key");
                release.countDown();
                if (fail) {
                    assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(AttachmentStorageException.class);
                } else {
                    assertThat(running.get(5, TimeUnit.SECONDS).downloadUrl()).isEqualTo("https://download.test");
                }
                assertThat(service.issueDownloadUrl(3L, List.of(1L)).downloadUrl()).isEqualTo("https://download.test");
            } finally {
                release.countDown();
            }
        }
    }
}
