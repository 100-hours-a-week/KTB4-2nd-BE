package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripProcessingCannotBeCanceledException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TripProcessingCancellationServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripPhotoAnalysisService analysis = mock(TripPhotoAnalysisService.class);
    private final InitialUploadExecutionRegistry executions = mock(InitialUploadExecutionRegistry.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private TripProcessingCancellationService service;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new TripProcessingCancellationService(
                trips, regions, places, attachments, files, storage, analysis, executions, transactions);
    }

    @Test
    void 처리중_여행과_연관_데이터를_같은_시각에_취소하고_커밋_후_외부_객체를_정리한다() {
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        ReflectionTestUtils.setField(file, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        when(trips.cancelProcessing(eq(7L), eq(1L), eq(ProcessingStatus.PROCESSING),
                eq(ProcessingStatus.CANCELED), any(LocalDateTime.class))).thenReturn(1);
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L)).thenReturn(List.of(attachment));
        when(files.findAllById(List.of(20L))).thenReturn(List.of(file));
        when(executions.cancel(7L)).thenReturn(true);

        service.cancel(7L, 1L);

        verify(analysis).cancel(7L);
        verify(executions).cancel(7L);
        verify(storage).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        verify(regions).softDeleteByTripId(eq(7L), any(LocalDateTime.class));
        verify(places).softDeleteByTripId(eq(7L), any(LocalDateTime.class));
        verify(attachments).softDeleteByTripId(eq(7L), any(LocalDateTime.class));
        verify(files).softDeleteByIds(eq(List.of(20L)), any(LocalDateTime.class));
    }

    @Test
    void 없는_여행과_다른_소유자는_404로_숨긴다() {
        when(trips.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class, () -> service.cancel(7L, 1L));

        verifyNoInteractions(storage, analysis);
    }

    @Test
    void 이미_취소됐거나_완료된_여행은_409로_거부한다() {
        Trip canceled = trip(ProcessingStatus.CANCELED, LocalDateTime.now());
        Trip completed = trip(ProcessingStatus.COMPLETED, null);
        when(trips.findByIdAndUserId(7L, 1L))
                .thenReturn(Optional.of(canceled), Optional.of(completed));

        assertThrows(TripProcessingCannotBeCanceledException.class, () -> service.cancel(7L, 1L));
        assertThrows(TripProcessingCannotBeCanceledException.class, () -> service.cancel(7L, 1L));
    }

    @Test
    void 일반_삭제된_여행은_404로_처리한다() {
        when(trips.findByIdAndUserId(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING, LocalDateTime.now())));

        assertThrows(TripNotFoundException.class, () -> service.cancel(7L, 1L));
    }

    @Test
    void 커밋_후_AI와_S3_정리_실패는_취소_성공을_되돌리지_않는다() {
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        ReflectionTestUtils.setField(file, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        when(trips.cancelProcessing(eq(7L), eq(1L), eq(ProcessingStatus.PROCESSING),
                eq(ProcessingStatus.CANCELED), any(LocalDateTime.class))).thenReturn(1);
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L)).thenReturn(List.of(attachment));
        when(files.findAllById(List.of(20L))).thenReturn(List.of(file));
        when(executions.cancel(7L)).thenReturn(true);
        doThrow(new IllegalStateException("AI")).when(analysis).cancel(7L);
        doThrow(new IllegalStateException("S3")).when(storage).delete(any());

        assertDoesNotThrow(() -> service.cancel(7L, 1L));
    }

    @Test
    void AI가_시작되지_않았다면_레지스트리만_취소하고_AI_API는_호출하지_않는다() {
        when(trips.cancelProcessing(eq(7L), eq(1L), eq(ProcessingStatus.PROCESSING),
                eq(ProcessingStatus.CANCELED), any(LocalDateTime.class))).thenReturn(1);

        service.cancel(7L, 1L);

        verify(executions).cancel(7L);
        verifyNoInteractions(analysis);
    }

    private Trip trip(ProcessingStatus status, LocalDateTime deletedAt) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        ReflectionTestUtils.setField(trip, "deletedAt", deletedAt);
        return trip;
    }
}
