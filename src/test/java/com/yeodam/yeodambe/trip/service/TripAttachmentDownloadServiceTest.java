package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentDownloadServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository =
            mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient tripAttachmentStorageClient =
            mock(TripAttachmentStorageClient.class);
    private final TripAttachmentDownloadService service =
            new TripAttachmentDownloadService(
                    tripAttachmentRepository,
                    tripAttachmentStorageClient
            );

    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowLog.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    TripAttachmentDownloadServiceTest() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 접근_가능한_첨부의_다운로드_URL을_반환한다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);

        when(tripAttachmentRepository.findAccessibleById(501L, 1L))
                .thenReturn(Optional.of(attachment));
        when(attachment.getId()).thenReturn(501L);
        when(attachment.getDisplayStorageKey()).thenReturn("trip-displays/display-501.jpg");
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn("trip-uploads/original-501");
        when(file.getOriginalFileName()).thenReturn("서울 여행.heic");
        when(tripAttachmentStorageClient.createDownloadUrl(
                "trip-uploads/original-501",
                "서울 여행.heic"
        )).thenReturn("https://example.com/download-501");

        TripAttachmentDownloadResponse response = service.issueDownloadUrl(1L, 501L);

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getKeyValuePairs())
                    .extracting(pair -> pair.key, pair -> pair.value)
                    .contains(tuple("stage", "original_download_url"), tuple("result", "success"),
                            tuple("object_key", "trip-uploads/original-501"));
            assertThat(event.getKeyValuePairs().toString())
                    .doesNotContain("https://example.com/download-501", "서울 여행.heic");
        });

        assertThat(response.tripAttachmentId()).isEqualTo(501L);
        assertThat(response.downloadUrl())
                .isEqualTo("https://example.com/download-501");
        verify(tripAttachmentStorageClient).createDownloadUrl(
                "trip-uploads/original-501",
                "서울 여행.heic"
        );
    }

    @Test
    void 접근할_수_없는_첨부는_찾을_수_없음으로_처리한다() {
        when(tripAttachmentRepository.findAccessibleById(501L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueDownloadUrl(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(tripAttachmentStorageClient);
    }
}
