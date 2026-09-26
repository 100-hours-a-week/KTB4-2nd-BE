package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripObjectCleanupServiceTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripObjectCleanupService service = new TripObjectCleanupService(
            attachments, files, trips, places, storage);
    private TripAttachment attachment;

    @BeforeEach
    void setUp() {
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        attachment.softDelete(LocalDateTime.now());
        ReflectionTestUtils.setField(attachment, "id", 30L);
        ReflectionTestUtils.setField(attachment, "file", file);
        when(attachments.findPendingCleanupByIds(List.of(30L)))
                .thenReturn(List.of(attachment));
    }

    @Test
    void 참조되지_않은_세_객체를_삭제하고_첨부를_DELETED로_표시한다() {
        service.process(List.of(30L));

        verify(storage).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.DELETED);
    }

    @ParameterizedTest
    @EnumSource(ActiveReference.class)
    void 모든_활성_참조_유형이_객체_삭제를_막는다(ActiveReference reference) {
        switch (reference) {
            case FILE -> when(files.existsByObjectKeyAndDeletedAtIsNull("original")).thenReturn(true);
            case ANALYZE -> when(attachments.existsByAnalyzeStorageKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case PREVIEW -> when(attachments.existsByPreviewStorageKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case TRIP_THUMBNAIL -> when(trips.existsByThumbnailKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case PLACE_THUMBNAIL -> when(places.existsByThumbnailKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
        }

        service.process(List.of(30L));

        verify(storage, never()).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.DELETED);
    }

    @Test
    void 하나라도_삭제에_실패하면_DELETED로_표시하지_않아_재시도한다() {
        doThrow(new IllegalStateException("S3")).when(storage).delete("preview");

        service.process(List.of(30L));

        assertThat(attachment.getClassificationStatus()).isNotEqualTo(ClassificationStatus.DELETED);
    }

    private enum ActiveReference {
        FILE,
        ANALYZE,
        PREVIEW,
        TRIP_THUMBNAIL,
        PLACE_THUMBNAIL
    }
}
