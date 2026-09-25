package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserStatsServiceTest {
    private final UserStatsRepository userStats = mock(UserStatsRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final UserStats stats = new UserStats(mock(User.class));
    private UserStatsService service;

    @BeforeEach
    void setUp() {
        service = new UserStatsService(userStats, trips, attachments, storage);
        when(userStats.findActiveByUserIdForUpdate(1L)).thenReturn(Optional.of(stats));
    }

    @Test
    void 조회된_여행과_첨부의_세_객체키로_통계를_교체한다() {
        TripStorageObjectKeys active = keys("original-1", "analyze-1", "preview-1");
        TripStorageObjectKeys unclassified = keys("original-2", "analyze-2", "preview-2");
        when(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(
                1L, ProcessingStatus.COMPLETED)).thenReturn(2L);
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(active, unclassified));
        when(storage.size(anyString())).thenReturn(10L);

        service.refreshFromActiveTrips(1L);

        assertThat(stats.getTripCount()).isEqualTo(2L);
        assertThat(stats.getAttachmentCount()).isEqualTo(2L);
        assertThat(stats.getStorageUsedBytes()).isEqualTo(60L);
    }

    @Test
    void 중복_객체키는_한번만_합산한다() {
        TripStorageObjectKeys first = keys("shared", "shared", "preview-1");
        TripStorageObjectKeys second = keys("shared", "analyze-2", "preview-2");
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(first, second));
        when(storage.size(anyString())).thenReturn(10L);

        service.refreshFromActiveTrips(1L);

        verify(storage).size("shared");
        assertThat(stats.getStorageUsedBytes()).isEqualTo(40L);
    }

    @Test
    void 음수_통계로_교체하지_않는다() {
        assertThatThrownBy(() -> stats.replaceActiveTripUsage(-1, 0, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    private TripStorageObjectKeys keys(String originalKey, String analyzeKey, String previewKey) {
        return new TripStorageObjectKeys(originalKey, analyzeKey, previewKey);
    }
}
