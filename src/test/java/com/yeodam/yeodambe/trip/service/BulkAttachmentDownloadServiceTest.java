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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.yeodam.yeodambe.common.logging.WorkflowLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkAttachmentDownloadServiceTest {

    @Mock
    private TripAttachmentRepository tripAttachmentRepository;
    @Mock
    private TripAttachmentStorageClient tripAttachmentStorageClient;
    @Mock
    private TripEditAuthorizationService access;
    @InjectMocks
    private BulkAttachmentDownloadService service;
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(BulkAttachmentDownloadService.class);
    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            logger.detachAppender(logAppender);
            ((Logger) LoggerFactory.getLogger(WorkflowLog.class)).detachAppender(logAppender);
            MDC.clear();
            logAppender.stop();
        }
    }

    @Test
    void 접근_가능한_첨부를_ZIP으로_저장하고_다운로드_URL을_반환한다() throws IOException {
        startLogAppender();
        TripAttachment first = attachment(501L, 1L, "first-key", "photo.heic");
        TripAttachment second = attachment(502L, 1L, "second-key", "photo.jpg");
        when(first.getDisplayStorageKey()).thenReturn("display-key");
        AtomicReference<byte[]> archiveBytes = new AtomicReference<>();
        AtomicReference<Path> archivePath = new AtomicReference<>();

        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L, 502L)))
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
                .containsExactly("photo.heic", "photo.jpg");
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
        List<ILoggingEvent> downloads = logAppender.list.stream()
                .filter(event -> "s3_original_download".equals(keyValue(event, "stage")))
                .toList();
        assertThat(downloads).hasSize(2);
        assertThat(downloads).allSatisfy(event -> {
            assertThat(keyValue(event, "result")).isEqualTo("success");
            assertThat(keyValue(event, "bytes_transferred")).isInstanceOf(Long.class);
        });

    }

    @Test
    void 임시_ZIP_업로드가_실패하면_업로드_단계_로그를_남긴다() {
        startLogAppender();
        TripAttachment attachment = attachment(501L, 1L, "first-key", "photo.jpg");

        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
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
    void 공유_읽기_권한이_없으면_찾을_수_없음으로_처리한다() {
        TripAttachment attachment = attachment(501L, 2L, "other-user-key", "photo.jpg");

        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(attachment));

        Trip trip = attachment.getTrip();
        doThrow(new TripNotFoundException()).when(access).requireReadPermission(trip, 1L);

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

    @Test
    void 원본_스트림을_읽다가_실패하면_다운로드_성공을_기록하지_않는다() {
        startLogAppender();
        TripAttachment first = attachment(501L, 1L, "first-key", "photo.jpg");
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(first));
        when(tripAttachmentStorageClient.open("first-key")).thenReturn(new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("private-source");
            }
        });

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L)))
                .isInstanceOf(IllegalStateException.class);

        List<ILoggingEvent> downloads = logAppender.list.stream()
                .filter(event -> "s3_original_download".equals(keyValue(event, "stage")))
                .toList();
        assertThat(downloads).hasSize(1);
        assertThat(keyValue(downloads.getFirst(), "result")).isEqualTo("failure");
        assertThat(keyValue(downloads.getFirst(), "object_key")).isEqualTo("first-key");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 200})
    void 사진_수와_무관하게_여행_권한을_한번만_검증한다(int count) throws IOException {
        List<Long> ids = IntStream.rangeClosed(1, count).mapToObj(index -> (long) index).toList();
        List<TripAttachment> photos = ids.stream()
                .map(id -> attachment(id, 1L, "key-" + id, "photo-" + id + ".jpg"))
                .toList();
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(ids)).thenReturn(photos);
        when(tripAttachmentStorageClient.open(any(String.class)))
                .thenAnswer(call -> new ByteArrayInputStream(new byte[]{1}));
        when(tripAttachmentStorageClient.storeDownloadArchive(any(Path.class))).thenReturn("archive");
        when(tripAttachmentStorageClient.createDownloadUrl("archive", "yeodam-attachments.zip"))
                .thenReturn("https://example.com/shared-archive");

        assertThat(service.issueDownloadUrl(2L, ids).downloadUrl())
                .isEqualTo("https://example.com/shared-archive");
        verify(access, times(1)).requireReadPermission(photos.getFirst().getTrip(), 2L);
        verifyNoMoreInteractions(access);
    }

    @Test
    void 접근_가능한_다른_여행의_사진이_섞이면_입력_오류로_거절한다() {
        TripAttachment first = attachment(501L, 1L, "first", "first.jpg");
        TripAttachment second = attachment(502L, 1L, "second", "second.jpg");
        when(second.getTripId()).thenReturn(8L);
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L, 502L)))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L, 502L)))
                .isInstanceOf(InvalidAttachmentIdsException.class);
        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void 다른_여행의_사진이_섞여도_접근_불가능한_사진은_찾을_수_없음으로_처리한다(int deniedIndex) {
        TripAttachment first = attachment(501L, 1L, "first", "first.jpg");
        TripAttachment second = attachment(502L, 2L, "second", "second.jpg");
        when(second.getTripId()).thenReturn(8L);
        List<TripAttachment> attachments = List.of(first, second);
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L, 502L)))
                .thenReturn(attachments);
        Trip deniedTrip = attachments.get(deniedIndex).getTrip();
        doThrow(new TripNotFoundException()).when(access).requireReadPermission(deniedTrip, 3L);

        assertThatThrownBy(() -> service.issueDownloadUrl(3L, List.of(501L, 502L)))
                .isInstanceOf(AttachmentNotFoundException.class);
        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 조회된_사진이_부족하면_권한_검사_전에_거절한다() {
        TripAttachment first = attachment(501L, 1L, "first", "first.jpg");
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L, 502L)))
                .thenReturn(List.of(first));

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L, 502L)))
                .isInstanceOf(AttachmentNotFoundException.class);
        verifyNoInteractions(access, tripAttachmentStorageClient);
    }

    @Test
    void 권한_DB_장애는_404로_숨기지_않는다() {
        TripAttachment first = attachment(501L, 1L, "first", "first.jpg");
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(first));
        Trip trip = first.getTrip();
        doThrow(new DataAccessResourceFailureException("DB 실패"))
                .when(access).requireReadPermission(trip, 1L);

        assertThatThrownBy(() -> service.issueDownloadUrl(1L, List.of(501L)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 비어있거나_잘못된_ID는_조회_전에_거절한다() {
        for (List<Long> ids : java.util.Arrays.asList(null, List.<Long>of(),
                java.util.Arrays.asList(501L, null), List.of(0L), List.of(-1L))) {
            assertThatThrownBy(() -> service.issueDownloadUrl(1L, ids))
                    .isInstanceOf(InvalidAttachmentIdsException.class);
        }
        verifyNoInteractions(tripAttachmentRepository, access, tripAttachmentStorageClient);
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
        when(attachment.getTripId()).thenReturn(7L);
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
        ((Logger) LoggerFactory.getLogger(WorkflowLog.class)).addAppender(appender);
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
