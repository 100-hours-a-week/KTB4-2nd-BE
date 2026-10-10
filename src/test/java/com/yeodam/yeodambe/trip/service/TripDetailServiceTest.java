package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TripDetailServiceTest {
    private TripAccessService tripAccessService;
    private TripRegionRepository tripRegionRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private StoryRepository storyRepository;
    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripAccessService = mock(TripAccessService.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        storyRepository = mock(StoryRepository.class);
        tripService = new TripService(
                mock(TripRepository.class),
                tripRegionRepository,
                mock(RegionCatalog.class),
                tripAttachmentRepository,
                mock(TripAttachmentStorageClient.class),
                tripAccessService,
                new tools.jackson.databind.ObjectMapper(),
                storyRepository
        );
    }

    @Test
    void 완료된_당일치기_여행의_상세를_반환한다() {
        Trip trip = trip(ProcessingStatus.COMPLETED);
        TripRegion first = region(31L, "50110", "제주특별자치도 제주시");
        TripRegion second = region(32L, "50130", "제주특별자치도 서귀포시");
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenReturn(trip);
        when(tripRegionRepository.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L))
                .thenReturn(List.of(first, second));
        when(tripAttachmentRepository.countActiveByTripId(7L)).thenReturn(3L);
        when(tripAttachmentRepository.countUnclassifiedByIssue(7L)).thenReturn(List.of(
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.UNCLEAR_LOCATION, 1L),
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.BLURRY, 2L),
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.DUPLICATED, 3L)
        ));

        var response = tripService.findTripDetail(7L, 1L);

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.tripName()).isEqualTo("제주 여행");
        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.nightCount()).isZero();
        assertThat(response.regions()).extracting(region -> region.regionId())
                .containsExactly(31L, 32L);
        assertThat(response.regions()).extracting(region -> region.regionCode())
                .containsExactly("50110", "50130");
        assertThat(response.regions()).extracting(region -> region.regionName())
                .containsExactly("제주특별자치도 제주시", "제주특별자치도 서귀포시");
        assertThat(response.attachmentCount()).isEqualTo(3L);
        assertThat(response.unclassifiedAttachmentCount()).isEqualTo(6L);
        assertThat(response.hasStory()).isFalse();
        assertThat(response.isFavorite()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED"})
    void 완료되지_않은_여행은_상세를_조회할_수_없다(ProcessingStatus status) {
        Trip trip = trip(status);
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenReturn(trip);

        assertThrows(TripDetailNotAvailableException.class,
                () -> tripService.findTripDetail(7L, 1L));
        verifyNoInteractions(tripRegionRepository, tripAttachmentRepository, storyRepository);
    }

    @Test
    void 취소_상태가_남아있어도_찾을_수_없음으로_처리한다() {
        Trip trip = trip(ProcessingStatus.CANCELED);
        when(tripAccessService.requireReadableTrip(7L, 1L))
                .thenReturn(trip);

        assertThrows(TripNotFoundException.class,
                () -> tripService.findTripDetail(7L, 1L));
        verifyNoInteractions(tripRegionRepository, tripAttachmentRepository, storyRepository);
    }

    @Test
    void 미분류_사진과_현재_스토리가_없으면_0과_false를_반환한다() {
        Trip trip = trip(ProcessingStatus.COMPLETED);
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenReturn(trip);
        when(tripAttachmentRepository.countUnclassifiedByIssue(7L)).thenReturn(List.of());
        when(storyRepository.findCurrentCompletedByTripId(7L)).thenReturn(Optional.empty());

        var response = tripService.findTripDetail(7L, 1L);

        assertThat(response.unclassifiedAttachmentCount()).isZero();
        assertThat(response.hasStory()).isFalse();
        verify(storyRepository).findCurrentCompletedByTripId(7L);
    }

    @Test
    void 현재_완료_스토리가_있으면_hasStory는_true다() {
        Trip trip = trip(ProcessingStatus.COMPLETED);
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenReturn(trip);
        Story story = mock(Story.class);
        when(storyRepository.findCurrentCompletedByTripId(7L))
                .thenReturn(Optional.of(story));

        var response = tripService.findTripDetail(7L, 1L);

        assertThat(response.hasStory()).isTrue();
        verify(storyRepository).findCurrentCompletedByTripId(7L);
    }

    @Test
    void 접근할_수_없는_여행은_상세_집계를_실행하지_않는다() {
        when(tripAccessService.requireReadableTrip(7L, 1L))
                .thenThrow(new TripNotFoundException());

        assertThrows(TripNotFoundException.class,
                () -> tripService.findTripDetail(7L, 1L));

        verifyNoInteractions(tripRegionRepository, tripAttachmentRepository, storyRepository);
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        when(trip.getTripName()).thenReturn("제주 여행");
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(trip.getFavorite()).thenReturn(true);
        when(trip.getProcessingStatus()).thenReturn(status);
        return trip;
    }

    private TripRegion region(Long id, String code, String name) {
        TripRegion region = mock(TripRegion.class);
        when(region.getId()).thenReturn(id);
        when(region.getRegionCode()).thenReturn(code);
        when(region.getRegionName()).thenReturn(name);
        return region;
    }
}
