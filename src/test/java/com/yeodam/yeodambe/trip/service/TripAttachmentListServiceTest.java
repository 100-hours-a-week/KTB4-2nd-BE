package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentListServiceTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripDetailPlaceRepository tripDetailPlaceRepository = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository tripAttachmentRepository = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storageClient = mock(TripAttachmentStorageClient.class);
    private final TripAttachmentListService service = new TripAttachmentListService(
            tripRepository,
            tripDetailPlaceRepository,
            tripAttachmentRepository,
            storageClient,
            new ObjectMapper()
    );

    @Test
    void 열아홉개를_조회해_앞_열여덟개와_다음_커서를_반환한다() {
        List<TripAttachment> attachments = attachments(19);
        prepareOwnedPlace();
        when(tripAttachmentRepository.findByPlaceFolderWithCursor(
                eq(7L), eq(3L), eq(ClassificationStatus.ACTIVE),
                eq(null), eq(null), any(Pageable.class)
        )).thenReturn(attachments);
        when(storageClient.createReadUrl(any(String.class)))
                .thenAnswer(invocation -> "https://example.com/" + invocation.getArgument(0));

        TripAttachmentListResponse response = service.findByPlaceFolder(1L, 7L, 3L, null);

        assertThat(response.items()).hasSize(18);
        assertThat(response.items().getFirst().tripAttachmentId()).isEqualTo(100L);
        assertThat(response.items().getFirst().thumbnailUrl()).isEqualTo("https://example.com/preview-100");
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        verify(storageClient).createReadUrl("preview-100");
        verify(tripAttachmentRepository).findByPlaceFolderWithCursor(
                eq(7L), eq(3L), eq(ClassificationStatus.ACTIVE),
                eq(null), eq(null), any(Pageable.class)
        );
    }

    @Test
    void 다음_페이지는_이번_응답의_마지막_사진_다음부터_조회한다() {
        List<TripAttachment> attachments = attachments(19);
        prepareOwnedPlace();
        when(tripAttachmentRepository.findByPlaceFolderWithCursor(
                eq(7L), eq(3L), eq(ClassificationStatus.ACTIVE),
                any(), any(), any(Pageable.class)
        )).thenReturn(attachments);
        when(storageClient.createReadUrl(any(String.class)))
                .thenReturn("https://example.com/preview");

        TripAttachmentListResponse firstPage = service.findByPlaceFolder(1L, 7L, 3L, null);
        service.findByPlaceFolder(1L, 7L, 3L, firstPage.nextCursor());

        TripAttachment lastReturnedAttachment = attachments.get(17);
        LocalDateTime cursorCreatedAt = lastReturnedAttachment.getCreatedAt();
        Long cursorId = lastReturnedAttachment.getId();
        verify(tripAttachmentRepository).findByPlaceFolderWithCursor(
                eq(7L), eq(3L), eq(ClassificationStatus.ACTIVE),
                eq(cursorCreatedAt), eq(cursorId), any(Pageable.class)
        );
    }

    @Test
    void 빈_커서는_조회_전에_거절한다() {
        prepareOwnedPlace();

        assertThatThrownBy(() -> service.findByPlaceFolder(1L, 7L, 3L, "   "))
                .isInstanceOf(InvalidCursorException.class);

        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 소유하지_않은_여행은_장소_폴더를_찾을_수_없다고_처리한다() {
        when(tripRepository.existsByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(false);

        assertThatThrownBy(() -> service.findByPlaceFolder(1L, 7L, 3L, null))
                .isInstanceOf(PlaceFolderNotFoundException.class);

        verifyNoInteractions(tripDetailPlaceRepository, tripAttachmentRepository, storageClient);
    }

    private void prepareOwnedPlace() {
        when(tripRepository.existsByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(true);
        when(tripDetailPlaceRepository.existsByIdAndTripIdAndDeletedAtIsNull(3L, 7L))
                .thenReturn(true);
    }

    private List<TripAttachment> attachments(int count) {
        List<TripAttachment> attachments = new ArrayList<>();
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 22, 12, 0);

        for (int index = 0; index < count; index++) {
            long id = 100L - index;
            TripAttachment attachment = mock(TripAttachment.class);
            when(attachment.getId()).thenReturn(id);
            when(attachment.getCreatedAt()).thenReturn(createdAt.minusMinutes(index));
            when(attachment.getPreviewStorageKey()).thenReturn("preview-" + id);
            attachments.add(attachment);
        }

        return attachments;
    }
}
