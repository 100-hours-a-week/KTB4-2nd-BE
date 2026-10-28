package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripDetailMapMarker;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripDetailMapResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
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
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final AdditionalAttachmentExecutionRepository executions =
            mock(AdditionalAttachmentExecutionRepository.class);

    @BeforeEach
    void setUp() {
        tripAccessService = mock(TripAccessService.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        storyRepository = mock(StoryRepository.class);
        tripService = new TripService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class),
                mock(TripRepository.class),
                tripRegionRepository,
                executions,
                mock(RegionCatalog.class),
                tripAttachmentRepository,
                storage,
                tripAccessService,
                new tools.jackson.databind.ObjectMapper(),
                storyRepository,
                mock(TripMapCacheStore.class),
                Clock.systemUTC(),
                places
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

    @Test
    void 지도_마커의_좌표와_첨부수와_대표_URL을_반환한다() {
        doReturn(trip(ProcessingStatus.COMPLETED)).when(tripAccessService).requireReadableTrip(7L, 1L);
        when(places.findMapMarkers(7L)).thenReturn(List.of(
                new TripDetailMapMarker(11L, "성산일출봉", new BigDecimal("33.45800000"),
                        new BigDecimal("126.94230000"), "preview/11", 42L)
        ));
        when(storage.createReadUrl("preview/11")).thenReturn("https://example.test/11");

        TripDetailMapResponse response = tripService.findTripDetailMap(7L, 1L);

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.markers()).containsExactly(new TripDetailMapResponse.Marker(
                11L, "성산일출봉", new BigDecimal("33.45800000"),
                new BigDecimal("126.94230000"), 42L, "https://example.test/11"
        ));
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 지도에_장소가_없으면_빈_배열을_반환한다() {
        doReturn(trip(ProcessingStatus.COMPLETED)).when(tripAccessService).requireReadableTrip(7L, 1L);
        when(places.findMapMarkers(7L)).thenReturn(List.of());

        TripDetailMapResponse response = tripService.findTripDetailMap(7L, 1L);

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.markers()).isEmpty();
        verifyNoInteractions(storage, tripAttachmentRepository);
    }

    @Test
    void 대표_키가_없거나_공백이면_URL은_null이다() {
        doReturn(trip(ProcessingStatus.COMPLETED)).when(tripAccessService).requireReadableTrip(7L, 1L);
        when(places.findMapMarkers(7L)).thenReturn(List.of(
                new TripDetailMapMarker(11L, "A", BigDecimal.ONE, BigDecimal.TEN, null, 1L),
                new TripDetailMapMarker(12L, "B", BigDecimal.ONE, BigDecimal.TEN, "  ", 2L)
        ));

        TripDetailMapResponse response = tripService.findTripDetailMap(7L, 1L);

        assertThat(response.markers()).extracting(TripDetailMapResponse.Marker::thumbnailUrl)
                .containsExactly(null, null);
        verifyNoInteractions(storage);
    }

    @Test
    void 지도_URL_생성_실패를_그대로_전파한다() {
        doReturn(trip(ProcessingStatus.COMPLETED)).when(tripAccessService).requireReadableTrip(7L, 1L);
        when(places.findMapMarkers(7L)).thenReturn(List.of(
                new TripDetailMapMarker(11L, "A", BigDecimal.ONE, BigDecimal.TEN, "preview/11", 1L)
        ));
        IllegalStateException failure = new IllegalStateException("signing failed");
        when(storage.createReadUrl("preview/11")).thenThrow(failure);

        assertThat(assertThrows(IllegalStateException.class,
                () -> tripService.findTripDetailMap(7L, 1L))).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 지도는_비완료와_취소_여행의_조회와_URL_생성을_차단한다(ProcessingStatus status) {
        doReturn(trip(status)).when(tripAccessService).requireReadableTrip(7L, 1L);
        Class<? extends RuntimeException> exceptionType = status == ProcessingStatus.CANCELED
                ? TripNotFoundException.class : TripDetailNotAvailableException.class;

        assertThrows(exceptionType, () -> tripService.findTripDetailMap(7L, 1L));
        verifyNoInteractions(places, storage, executions);
    }

    @Test
    void 접근_불가와_사진_추가_중에는_지도를_조회하지_않는다() {
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenThrow(new TripNotFoundException());
        assertThrows(TripNotFoundException.class, () -> tripService.findTripDetailMap(7L, 1L));
        verifyNoInteractions(executions);

        doReturn(trip(ProcessingStatus.COMPLETED)).when(tripAccessService).requireReadableTrip(7L, 1L);
        when(executions.existsByTripIdAndEndedAtIsNull(7L)).thenReturn(true);
        assertThrows(TripDetailNotAvailableException.class, () -> tripService.findTripDetailMap(7L, 1L));
        assertThrows(TripDetailNotAvailableException.class, () -> tripService.findTripDetail(7L, 1L));
        verifyNoInteractions(places, storage, tripRegionRepository, tripAttachmentRepository);
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
