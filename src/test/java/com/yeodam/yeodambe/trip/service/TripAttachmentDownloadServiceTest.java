package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void 접근_가능한_첨부의_다운로드_URL을_반환한다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);

        when(tripAttachmentRepository.findAccessibleById(501L, 1L))
                .thenReturn(Optional.of(attachment));
        when(attachment.getId()).thenReturn(501L);
        when(attachment.getFile()).thenReturn(file);
        when(file.getObjectKey()).thenReturn("trip-uploads/original-501");
        when(file.getOriginalFileName()).thenReturn("서울 여행.jpg");
        when(tripAttachmentStorageClient.createDownloadUrl(
                "trip-uploads/original-501",
                "서울 여행.jpg"
        )).thenReturn("https://example.com/download-501");

        TripAttachmentDownloadResponse response = service.issueDownloadUrl(1L, 501L);

        assertThat(response.tripAttachmentId()).isEqualTo(501L);
        assertThat(response.downloadUrl())
                .isEqualTo("https://example.com/download-501");
        verify(tripAttachmentStorageClient).createDownloadUrl(
                "trip-uploads/original-501",
                "서울 여행.jpg"
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
