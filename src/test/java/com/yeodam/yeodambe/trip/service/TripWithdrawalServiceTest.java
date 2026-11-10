package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TripWithdrawalServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final TripMapCacheStore mapCache = mock(TripMapCacheStore.class);
    private final TripWithdrawalService service = new TripWithdrawalService(
            trips, regions, places, attachments, shares, files,
            mock(InitialAttachmentUploadTransactionService.class),
            mock(AdditionalAttachmentUploadTransactionService.class), mapCache);

    @Test
    void 탈퇴_성공시_소프트_삭제_후_지도_캐시_무효화를_예약한다() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 14, 12, 0);
        service.withdrawAll(1L, at);
        var ordered = inOrder(trips, mapCache);
        ordered.verify(trips).findAllOwnedActiveForUpdate(1L);
        ordered.verify(trips).softDeleteByUserId(1L, at);
        ordered.verify(mapCache).evictAfterCommit(1L);
        verify(attachments).softDeleteByUserId(1L, at);
        verify(shares).softDeleteByUserId(1L, at);
        verify(files).softDeleteByUserId(1L, at);
    }

    @Test
    void 소프트_삭제_실패시_지도_캐시_무효화를_예약하지_않는다() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 14, 12, 0);
        doThrow(new IllegalStateException("delete failed")).when(regions).softDeleteByUserId(1L, at);
        assertThatThrownBy(() -> service.withdrawAll(1L, at)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapCache);
        verifyNoInteractions(shares);
    }
}
