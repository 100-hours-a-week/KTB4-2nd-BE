package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentDownloadResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkAttachmentDownloadServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository =
            mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient tripAttachmentStorageClient =
            mock(TripAttachmentStorageClient.class);
    private final BulkAttachmentDownloadService service =
            new BulkAttachmentDownloadService(
                    tripAttachmentRepository,
                    tripAttachmentStorageClient
            );
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(BulkAttachmentDownloadService.class);
    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void 접근_가능한_첨부를_ZIP으로_저장하고_다운로드_URL을_반환한다() throws IOException {
        startLogAppender();
        TripAttachment first = attachment(501L, 1L, "first-key", "photo.jpg");
        TripAttachment second = attachment(502L, 1L, "second-key", "photo.jpg");
        AtomicReference<byte[]> archiveBytes = new AtomicReference<>();
        AtomicReference<Path> archivePath = new AtomicReference<>();

        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L, 502L)))
                .thenReturn(List.of(first, second));
        when(tripAttachmentStorageClient.open("first-key"))
                .thenReturn(new ByteArrayInputStream("first".getBytes()));
        when(tripAttachmentStorageClient.open("second-key"))
                .thenReturn(new ByteArrayInputStream("second".getBytes()));
        when(tripAttachmentStorageClient.storeDownloadArchive(any(Path.class)))
                .thenAnswer(invocation -> {
                    Path archive = invocation.getArgument(0);
                    archivePath.set(archive);
                    archiveBytes.set(Files.readAllBytes(archive));
                    return "trip-downloads/archive.zip";
                });
        when(tripAttachmentStorageClient.createDownloadUrl(
                "trip-downloads/archive.zip",
                "yeodam-attachments.zip"
        )).thenReturn("https://example.com/archive");

        BulkAttachmentDownloadResponse response = service.issueDownloadUrl(
                1L,
                List.of(501L, 502L)
        );

        assertThat(response.fileName()).isEqualTo("yeodam-attachments.zip");
        assertThat(response.downloadUrl()).isEqualTo("https://example.com/archive");
        assertThat(entryNames(archiveBytes.get()))
                .containsExactly("photo.jpg", "photo (2).jpg");
        assertThat(Files.exists(archivePath.get())).isFalse();
        verify(tripAttachmentStorageClient).createDownloadUrl(
                "trip-downloads/archive.zip",
                "yeodam-attachments.zip"
        );

        ILoggingEvent success = logAppender.list.stream()
                .filter(event -> "attachment_bulk_download".equals(keyValue(event, "event")))
                .filter(event -> "success".equals(keyValue(event, "result")))
                .findFirst()
                .orElseThrow();
        assertThat(keyValue(success, "expected_count")).isEqualTo(2);
    }

    @Test
    void 임시_ZIP_업로드가_실패하면_업로드_단계_로그를_남긴다() {
        startLogAppender();
        TripAttachment attachment = attachment(501L, 1L, "first-key", "photo.jpg");

        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        when(tripAttachmentStorageClient.open("first-key"))
                .thenReturn(new ByteArrayInputStream("first".getBytes()));
        when(tripAttachmentStorageClient.storeDownloadArchive(any(Path.class)))
                .thenThrow(new IllegalStateException("S3 업로드 실패"));

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L)))
                .isInstanceOf(IllegalStateException.class);

        ILoggingEvent failure = logAppender.list.stream()
                .filter(event -> "attachment_bulk_download".equals(keyValue(event, "event")))
                .filter(event -> "failure".equals(keyValue(event, "result")))
                .findFirst()
                .orElseThrow();
        assertThat(keyValue(failure, "failure_stage")).isEqualTo("archive_upload");
        assertThat(keyValue(failure, "error_code")).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    void 다른_회원의_첨부가_하나라도_있으면_찾을_수_없음으로_처리한다() {
        TripAttachment attachment = attachment(501L, 2L, "other-user-key", "photo.jpg");

        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(attachment));

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L)))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 중복되거나_이백개를_초과한_ID는_조회_전에_거부한다() {
        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L, 501L)))
                .isInstanceOf(InvalidAttachmentIdsException.class);

        List<Long> tooManyIds = new ArrayList<>();
        for (long id = 1; id <= 201; id++) {
            tooManyIds.add(id);
        }

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, tooManyIds))
                .isInstanceOf(InvalidAttachmentIdsException.class);

        verifyNoInteractions(tripAttachmentRepository, tripAttachmentStorageClient);
    }

    private TripAttachment attachment(
            Long attachmentId,
            Long userId,
            String objectKey,
            String originalFileName
    ) {
        TripAttachment attachment = mock(TripAttachment.class);
        Trip trip = mock(Trip.class);
        StoredFile file = mock(StoredFile.class);

        when(attachment.getId()).thenReturn(attachmentId);
        when(attachment.getTrip()).thenReturn(trip);
        when(trip.getUserId()).thenReturn(userId);
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn(objectKey);
        when(file.getOriginalFileName()).thenReturn(originalFileName);

        return attachment;
    }

    private List<String> entryNames(byte[] archiveBytes) throws IOException {
        List<String> names = new ArrayList<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(archiveBytes)
        )) {
            java.util.zip.ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }

        return names;
    }

    private void startLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logAppender = appender;
    }

    private Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
