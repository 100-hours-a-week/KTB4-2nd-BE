package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentDetailServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository =
            mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient tripAttachmentStorageClient =
            mock(TripAttachmentStorageClient.class);
    private final TripAccessService access = mock(TripAccessService.class);
    private final TripAttachmentDetailService service =
            new TripAttachmentDetailService(
                    tripAttachmentRepository,
                    tripAttachmentStorageClient,
                    access
            );

    @Test
    void 공유_읽기_권한이_없으면_사진_URL을_발급하지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        when(attachment.getTripId()).thenReturn(7L);
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        doThrow(new TripNotFoundException()).when(access).requireReadableTrip(7L, 2L);

        assertThatThrownBy(() -> service.findDetail(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 접근_가능한_첨부의_원본_읽기_URL을_반환한다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);

        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        when(attachment.getId()).thenReturn(501L);
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn("trip-uploads/original-501");
        when(tripAttachmentStorageClient.createReadUrl(
                "trip-uploads/original-501"
        )).thenReturn("https://example.com/original-501");

        TripAttachmentDetailResponse response = service.findDetail(1L, 501L);

        assertThat(response.tripAttachmentId()).isEqualTo(501L);
        assertThat(response.originalUrl())
                .isEqualTo("https://example.com/original-501");
        verify(tripAttachmentStorageClient)
                .createReadUrl("trip-uploads/original-501");
    }

    @Test
    void 표시본이_있으면_표시본_읽기_URL을_originalUrl로_반환한다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(attachment));
        when(attachment.getId()).thenReturn(501L);
        when(attachment.getDisplayStorageKey()).thenReturn("display-501");
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn("original-501");
        when(tripAttachmentStorageClient.createReadUrl("display-501"))
                .thenReturn("https://example.com/display-501");

        TripAttachmentDetailResponse response = service.findDetail(1L, 501L);

        assertThat(response.originalUrl()).isEqualTo("https://example.com/display-501");
        verify(tripAttachmentStorageClient).createReadUrl("display-501");
    }

    @Test
    void 접근할_수_없는_첨부는_찾을_수_없음으로_처리한다() {
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.findDetail(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(tripAttachmentStorageClient);
    }
}
