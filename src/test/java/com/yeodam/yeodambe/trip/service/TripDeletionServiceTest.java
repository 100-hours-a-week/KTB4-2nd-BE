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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TripDeletionServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final UserStatsService userStats = mock(UserStatsService.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private TripDeletionService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        service = new TripDeletionService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class),
                trips, regions, places, attachments, files, userStats, transactions, meterRegistry);
    }

    @Test
    void 없는_여행은_404다() {
        assertThrows(TripNotFoundException.class, () -> service.delete(7L, 1L));
        verify(trips).findOwnedActiveForUpdate(7L, 1L);
        verifyNoInteractions(userStats);
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
        verify(files).softDeleteByIds(eq(List.of(20L)), any());
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "trip_delete", "outcome", "success")
                .timer()
                .count());
    }

    @Test
    void 다른_활성_첨부가_공유하는_파일은_삭제하지_않는다() {
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        when(trips.findOwnedActiveForUpdate(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(attachments.findAllByTripIdAndDeletedAtIsNull(7L))
                .thenReturn(List.of(attachment));
        when(attachments.existsByFileIdAndDeletedAtIsNull(20L)).thenReturn(true);

        service.delete(7L, 1L);

        verify(files, never()).softDeleteByIds(any(), any());
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        return trip;
    }
}
