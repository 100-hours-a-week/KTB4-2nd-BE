package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import org.junit.jupiter.api.AfterEach;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripAttachmentDownloadServiceTest {

    @Mock
    private TripAttachmentRepository tripAttachmentRepository;
    @Mock
    private TripAttachmentStorageClient tripAttachmentStorageClient;
    @Mock
    private TripEditAuthorizationService access;
    @InjectMocks
    private TripAttachmentDownloadService service;

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
        Trip trip = mock(Trip.class);
        StoredFile file = mock(StoredFile.class);

        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        when(attachment.getId()).thenReturn(501L);
        when(attachment.getTrip()).thenReturn(trip);
        when(attachment.getDisplayStorageKey()).thenReturn("trip-displays/display-501.jpg");
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn("trip-uploads/original-501");
        when(file.getOriginalFileName()).thenReturn("서울 여행.heic");
        when(tripAttachmentStorageClient.createDownloadUrl(
                "trip-uploads/original-501",
                "서울 여행.heic"
        )).thenReturn("https://example.com/download-501");

        TripAttachmentDownloadResponse response = service.issueDownloadUrl(2L, 501L);
        verify(access).requireReadPermission(trip, 2L);

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
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.issueDownloadUrl(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(access, tripAttachmentStorageClient);
    }

    @Test
    void 공유_읽기_권한이_없으면_URL을_발급하지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        Trip trip = mock(Trip.class);
        when(attachment.getTrip()).thenReturn(trip);
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        doThrow(new TripNotFoundException()).when(access).requireReadPermission(trip, 2L);

        assertThatThrownBy(() -> service.issueDownloadUrl(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);
        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 권한_DB_장애는_404로_숨기지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        Trip trip = mock(Trip.class);
        when(attachment.getTrip()).thenReturn(trip);
        when(tripAttachmentRepository.findAllActiveForDownloadByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        doThrow(new DataAccessResourceFailureException("DB 실패"))
                .when(access).requireReadPermission(trip, 2L);

        assertThatThrownBy(() -> service.issueDownloadUrl(2L, 501L))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(tripAttachmentStorageClient);
    }
}
