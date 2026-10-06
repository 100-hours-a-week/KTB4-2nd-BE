package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.InitialUploadExecutionRegistry;
import com.yeodam.yeodambe.trip.service.TripAttachmentTransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripAttachmentTransactionServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final InitialUploadExecutionRegistry executions = new InitialUploadExecutionRegistry();
    private final TripAttachmentTransactionService service = new TripAttachmentTransactionService(
            trips, files, attachments, executions, mock(com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository.class));

    @Test
    void 메모리에_예약된_요청만_실행_ID를_받는다() {
        when(trips.prepareInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED))
                .thenReturn(1);

        String executionId = service.reserve(7L, 1L).executionId();

        assertTrue(executions.isCurrent(7L, executionId));
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> service.reserve(7L, 1L));
    }

    @Test
    void 소유하지_않은_여행의_예약_실패는_메모리를_해제하고_404를_반환한다() {
        when(trips.existsByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(false);

        assertThrows(TripNotFoundException.class, () -> service.reserve(7L, 1L));
        assertDoesNotThrow(() -> executions.reserve(7L));
    }

    @Test
    void 재시작_후_남은_첨부_참조를_정리하고_객체_키를_반환한다() {
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        ReflectionTestUtils.setField(file, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(
                7L, 20L, "analyze", "preview", "display");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        when(trips.prepareInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED))
                .thenReturn(1);
        when(attachments.findAllByTripId(7L)).thenReturn(List.of(attachment));
        when(files.findAllById(List.of(20L))).thenReturn(List.of(file));

        var reservation = service.reserve(7L, 1L);

        assertEquals(List.of("original", "analyze", "preview", "display"), reservation.staleObjectKeys());
        verify(attachments).deleteAllInBatch(List.of(attachment));
        verify(files).deleteAllInBatch(List.of(file));
    }

    @Test
    void 첫_배치만_기존_참조를_정리하고_후속_배치는_보존한다() {
        when(trips.prepareInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED))
                .thenReturn(1);
        when(trips.findProcessableForUpdate(7L, 1L, ProcessingStatus.PROCESSING))
                .thenReturn(java.util.Optional.of(mock(com.yeodam.yeodambe.trip.entity.Trip.class)));

        var first = service.reserveBatch(7L, 1L, 1, 2);
        StoredFile storedFile = StoredFile.uploaded(1L, "one.jpg", "original-1", "image/jpeg");
        TripAttachment attachment = TripAttachment.initial(7L, null, "analyze-1", "preview-1");
        executions.completeBatch(7L, first.executionId(), 1, 3, List.of(
                new InitialUploadExecutionRegistry.StoredPhoto(
                        storedFile, attachment,
                        new DerivedPhotoKeys("original-1", "analyze-1", "preview-1"))), false);

        var second = service.reserveBatch(7L, 1L, 2, 2);

        assertEquals(first.executionId(), second.executionId());
        verify(trips, times(1)).prepareInitialUpload(
                7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
        verify(attachments, times(1)).findAllByTripId(7L);
        verify(attachments, times(1)).deleteAllInBatch(anyList());
        verify(files, times(1)).deleteAllInBatch(anyList());
    }

    @Test
    void 현재_배치_실패_정리는_참조만_삭제하고_여행_상태를_바꾸지_않는다() {
        service.deleteBatchReferences(List.of(20L), List.of(30L));

        verify(attachments).deleteAllByIdInBatch(List.of(30L));
        verify(files).deleteAllByIdInBatch(List.of(20L));
        verify(trips, never()).finishInitialUpload(any(), any(), any(), any());
    }

    @Test
    void 원본과_첨부_참조의_매핑을_저장소에_전달한다() {
        String executionId = executions.reserve(7L);
        var upload = new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", new byte[]{1});
        var derived = new com.yeodam.yeodambe.trip.service.DerivedPhotoKeys(
                "original", "analyze", "preview", "display", null, null, null, null,
                1L, 100L, 50L, 200L);
        when(trips.findProcessableForUpdate(
                7L, 1L, ProcessingStatus.PROCESSING)).thenReturn(java.util.Optional.of(mock(
                com.yeodam.yeodambe.trip.entity.Trip.class)));
        when(files.saveAll(anyList())).thenAnswer(invocation -> {
            List<StoredFile> saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved.getFirst(), "id", 20L);
            return saved;
        });
        when(attachments.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.saveFilesAndAttachments(7L, 1L, executionId,
                List.of(upload), List.of("original"), List.of("image/jpeg"), List.of(derived));

        assertEquals(20L, saved.attachments().getFirst().getFileId());
        assertEquals("analyze", saved.attachments().getFirst().getAnalyzeStorageKey());
        assertEquals("display", saved.attachments().getFirst().getDisplayStorageKey());
        assertEquals(1L, saved.originals().getFirst().getOriginalSizeBytes());
        assertEquals(100L, saved.attachments().getFirst().getAnalyzeSizeBytes());
        assertEquals(50L, saved.attachments().getFirst().getPreviewSizeBytes());
        assertEquals(200L, saved.attachments().getFirst().getDisplaySizeBytes());
        verify(files).saveAll(anyList());
        verify(attachments).saveAll(anyList());
    }

    @Test
    void 취소된_여행에는_첨부_참조를_저장하지_않는다() {
        String executionId = executions.reserve(7L);
        var upload = new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", new byte[]{1});
        var derived = new DerivedPhotoKeys(
                "original", "analyze", "preview", null, null, null, null);

        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> service.saveFilesAndAttachments(
                        7L,
                        1L,
                        executionId,
                        List.of(upload),
                        List.of("original"),
                        List.of("image/jpeg"),
                        List.of(derived)
                ));

        verifyNoInteractions(files, attachments);
    }
}
