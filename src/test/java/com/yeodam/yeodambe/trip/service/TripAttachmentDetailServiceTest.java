package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentDetailServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository =
            mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient tripAttachmentStorageClient =
            mock(TripAttachmentStorageClient.class);
    private final TripAttachmentDetailService service =
            new TripAttachmentDetailService(
                    tripAttachmentRepository,
                    tripAttachmentStorageClient
            );

    @Test
    void 접근_가능한_첨부의_원본_읽기_URL을_반환한다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);

        when(tripAttachmentRepository.findAccessibleById(501L, 1L))
                .thenReturn(Optional.of(attachment));
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
    void 접근할_수_없는_첨부는_찾을_수_없음으로_처리한다() {
        when(tripAttachmentRepository.findAccessibleById(501L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDetail(2L, 501L))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(tripAttachmentStorageClient);
    }
}
