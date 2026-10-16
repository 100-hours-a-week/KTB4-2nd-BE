package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripDeletionNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TripDeletionServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final UserStatsService userStats = mock(UserStatsService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TripMapCacheStore mapCache = mock(TripMapCacheStore.class);
    private final AttachmentCleanupScheduleStore cleanupSchedule = mock(AttachmentCleanupScheduleStore.class);
    private TripDeletionService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new TripDeletionService(
                mock(AdditionalAttachmentModificationGuard.class),
                trips, regions, places, attachments, files, userStats, transactionManager, meterRegistry, mapCache, cleanupSchedule);
    }

    @Test
    void 없는_여행은_404다() {
        assertThrows(TripNotFoundException.class, () -> service.delete(7L, 1L));
        verify(trips).findOwnedActiveForUpdate(7L, 1L);
        verifyNoInteractions(userStats);
        verifyNoInteractions(mapCache);
        verifyNoInteractions(cleanupSchedule);
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "trip_delete", "outcome", "failure")
                .timer()
                .count());
    }

    @Test
    void PROCESSING_여행은_409다() {
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));

        assertThrows(TripDeletionNotAllowedException.class, () -> service.delete(7L, 1L));
        verifyNoInteractions(userStats);
        verifyNoInteractions(mapCache);
        verifyNoInteractions(cleanupSchedule);
    }

    @Test
    void 완료_여행을_삭제하고_S3_객체는_보관한다() {
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L))
                .thenReturn(List.of(attachment));

        service.delete(7L, 1L);

        verify(userStats).refreshFromActiveTrips(1L);
        verify(mapCache).evictAfterCommit(1L);
        verify(files).softDeleteByIds(eq(List.of(20L)), any());
        var deletedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachments).softDeleteByTripId(eq(7L), deletedAt.capture());
        verify(cleanupSchedule).scheduleAfterCommit(List.of(30L),
                deletedAt.getValue().plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "trip_delete", "outcome", "success")
                .timer()
                .count());
    }

    @Test
    void 여러_파일을_참조_조회_없이_한번에_소프트_삭제한다() {
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L))
                .thenReturn(List.of(
                        TripAttachment.initial(7L, 20L, "analyze-a", "preview-a"),
                        TripAttachment.initial(7L, 21L, "analyze-b", "preview-b")));

        service.delete(7L, 1L);

        verify(attachments, never()).existsByFileIdAndDeletedAtIsNull(anyLong());
        verify(files).softDeleteByIds(eq(List.of(20L, 21L)), any());
        verify(userStats).refreshFromActiveTrips(1L);
    }

    @Test
    void 첨부가_없으면_파일_삭제를_호출하지_않는다() {
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L)).thenReturn(List.of());

        service.delete(7L, 1L);

        verifyNoInteractions(files);
        verify(attachments, never()).existsByFileIdAndDeletedAtIsNull(anyLong());
        verify(userStats).refreshFromActiveTrips(1L);
        verify(mapCache).evictAfterCommit(1L);
    }

    @Test
    void 통계_갱신_실패시_무효화를_예약하지_않고_롤백한다() {
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L)).thenReturn(List.of());
        doThrow(new IllegalStateException("stats failed"))
                .when(userStats).refreshFromActiveTrips(1L);

        assertThrows(IllegalStateException.class, () -> service.delete(7L, 1L));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(mapCache);
        verifyNoInteractions(cleanupSchedule);
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        return trip;
    }
}
