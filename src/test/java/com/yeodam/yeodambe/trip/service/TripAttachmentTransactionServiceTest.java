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
            trips, files, attachments, executions);

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
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        when(trips.prepareInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED))
                .thenReturn(1);
        when(attachments.findAllByTripId(7L)).thenReturn(List.of(attachment));
        when(files.findAllById(List.of(20L))).thenReturn(List.of(file));

        var reservation = service.reserve(7L, 1L);

        assertEquals(List.of("original", "analyze", "preview"), reservation.staleObjectKeys());
        verify(attachments).deleteAllInBatch(List.of(attachment));
        verify(files).deleteAllInBatch(List.of(file));
    }

    @Test
    void 원본과_첨부_참조의_매핑을_저장소에_전달한다() {
        String executionId = executions.reserve(7L);
        var upload = new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", new byte[]{1});
        var derived = new com.yeodam.yeodambe.trip.service.DerivedPhotoKeys(
                "original", "analyze", "preview", null, null, null, null);
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
