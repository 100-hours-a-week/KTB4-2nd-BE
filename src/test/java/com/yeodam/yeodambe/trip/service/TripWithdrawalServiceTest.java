package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.yeodam.yeodambe.trip.entity.Trip;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TripWithdrawalServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final TripMapCacheStore mapCache = mock(TripMapCacheStore.class);
    private final AttachmentCleanupScheduleStore cleanupSchedule = mock(AttachmentCleanupScheduleStore.class);
    private final InitialAttachmentUploadTransactionService uploadTransactions = mock(InitialAttachmentUploadTransactionService.class);
    private final AdditionalAttachmentUploadTransactionService additionalTransactions = mock(AdditionalAttachmentUploadTransactionService.class);
    private final TripWithdrawalService service = new TripWithdrawalService(
            trips, regions, places, attachments, files,
            uploadTransactions, additionalTransactions, mapCache, cleanupSchedule);

    @Test
    void successfulWithdrawalSchedulesMapInvalidationAfterSoftDeletes() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 14, 12, 0);
        when(attachments.findAllActiveIdsByUserId(1L)).thenReturn(List.of(10L, 20L));
        service.withdrawAll(1L, at);
        var ordered = inOrder(trips, mapCache);
        ordered.verify(trips).findAllOwnedActiveForUpdate(1L);
        ordered.verify(trips).softDeleteByUserId(1L, at);
        ordered.verify(mapCache).evictAfterCommit(1L);
        verify(attachments).softDeleteByUserId(1L, at);
        verify(files).softDeleteByUserId(1L, at);
        verify(cleanupSchedule).scheduleAfterCommit(List.of(10L, 20L),
                at.plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void failedSoftDeleteDoesNotScheduleInvalidation() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 14, 12, 0);
        doThrow(new IllegalStateException("delete failed")).when(regions).softDeleteByUserId(1L, at);
        assertThatThrownBy(() -> service.withdrawAll(1L, at)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapCache);
        verifyNoInteractions(cleanupSchedule);
    }

    @Test
    void 업로드_취소가_사진을_삭제하기_전에_ID를_확보한다() {
        var trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        when(trips.findAllOwnedActiveForUpdate(1L)).thenReturn(List.of(trip));
        when(attachments.findAllActiveIdsByUserId(1L)).thenReturn(List.of(10L));
        LocalDateTime at = LocalDateTime.of(2026, 10, 16, 12, 0);

        service.withdrawAll(1L, at);

        var ordered = inOrder(trips, attachments, uploadTransactions, additionalTransactions, cleanupSchedule);
        ordered.verify(trips).findAllOwnedActiveForUpdate(1L);
        ordered.verify(attachments).findAllActiveIdsByUserId(1L);
        ordered.verify(uploadTransactions).cancelExecution(7L, 1L);
        ordered.verify(additionalTransactions).cancelForWithdrawal(7L, 1L);
        ordered.verify(attachments).softDeleteByUserId(1L, at);
        ordered.verify(cleanupSchedule).scheduleAfterCommit(List.of(10L),
                at.plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void 업로드_취소에_실패하면_정리_예약을_등록하지_않는다() {
        var trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        when(trips.findAllOwnedActiveForUpdate(1L)).thenReturn(List.of(trip));
        doThrow(new IllegalStateException("cancel failed")).when(uploadTransactions).cancelExecution(7L, 1L);

        assertThatThrownBy(() -> service.withdrawAll(1L, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(cleanupSchedule);
    }
}
