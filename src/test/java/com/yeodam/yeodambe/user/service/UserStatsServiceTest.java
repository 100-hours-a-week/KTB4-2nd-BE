package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserStatsServiceTest {
    private final UserStatsRepository userStats = mock(UserStatsRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final UserStats stats = new UserStats(mock(User.class));
    private UserStatsService service;

    @BeforeEach
    void setUp() {
        service = new UserStatsService(userStats, trips, attachments);
        when(userStats.findActiveByUserIdForUpdate(1L)).thenReturn(Optional.of(stats));
    }

    @Test
    void DB에_저장된_원본과_파생_용량으로_통계를_교체한다() {
        TripStorageObjectKeys active = keys("original-1", "analyze-1", "preview-1", "display-1");
        TripStorageObjectKeys unclassified = keys("original-2", "analyze-2", "preview-2", null);
        when(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(
                1L, ProcessingStatus.COMPLETED)).thenReturn(2L);
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(active, unclassified));

        service.refreshFromActiveTrips(1L);

        assertThat(stats.getTripCount()).isEqualTo(2L);
        assertThat(stats.getAttachmentCount()).isEqualTo(2L);
        assertThat(stats.getStorageUsedBytes()).isEqualTo(70L);
    }

    @Test
    void 중복_객체키는_한번만_합산한다() {
        TripStorageObjectKeys first = keys("shared", "shared", "preview-1", "shared");
        TripStorageObjectKeys second = keys("shared", "analyze-2", "preview-2", null);
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(first, second));

        service.refreshFromActiveTrips(1L);

        assertThat(stats.getStorageUsedBytes()).isEqualTo(40L);
    }

    @Test
    void 음수_통계로_교체하지_않는다() {
        assertThatThrownBy(() -> stats.replaceActiveTripUsage(-1, 0, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void 키가_있는_파일의_용량이_누락되면_기존_통계를_유지하고_실패한다(int missingIndex) {
        Long[] sizes = {10L, 20L, 30L, 40L};
        sizes[missingIndex] = null;
        stats.replaceActiveTripUsage(3L, 5L, 500L);
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED)).thenReturn(List.of(
                new TripStorageObjectKeys("original", "analyze", "preview", "display",
                        sizes[0], sizes[1], sizes[2], sizes[3])));

        assertThatThrownBy(() -> service.refreshFromActiveTrips(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UserInternalErrorMessage.ATTACHMENT_STORAGE_SIZE_MISSING.message());

        assertThat(stats.getTripCount()).isEqualTo(3L);
        assertThat(stats.getAttachmentCount()).isEqualTo(5L);
        assertThat(stats.getStorageUsedBytes()).isEqualTo(500L);
    }

    @Test
    void 완료된_첨부가_없으면_용량과_첨부수를_0으로_갱신한다() {
        stats.replaceActiveTripUsage(1, 1, 100);
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED)).thenReturn(List.of());

        service.refreshFromActiveTrips(1L);

        assertThat(stats.getTripCount()).isZero();
        assertThat(stats.getAttachmentCount()).isZero();
        assertThat(stats.getStorageUsedBytes()).isZero();
    }

    @Test
    void 서로_다른_용량을_BIGINT_범위로_합산한다() {
        when(attachments.findAllForStats(1L, ProcessingStatus.COMPLETED)).thenReturn(List.of(
                new TripStorageObjectKeys("original", "analyze", "preview", null,
                        3_000_000_000L, 200L, 50L, null)));

        service.refreshFromActiveTrips(1L);

        assertThat(stats.getStorageUsedBytes()).isEqualTo(3_000_000_250L);
    }

    private TripStorageObjectKeys keys(
            String originalKey, String analyzeKey, String previewKey, String displayKey
    ) {
        return new TripStorageObjectKeys(originalKey, analyzeKey, previewKey, displayKey,
                10L, 10L, 10L, displayKey == null ? null : 10L);
    }
}
