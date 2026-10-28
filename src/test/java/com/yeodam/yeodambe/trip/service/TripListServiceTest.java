package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.service.request.TripListCursor;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import com.yeodam.yeodambe.trip.service.response.TripListItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripListServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private TripAttachmentStorageClient storageClient;
    private AdditionalAttachmentExecutionRepository executions;
    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        storageClient = mock(TripAttachmentStorageClient.class);
        executions = mock(AdditionalAttachmentExecutionRepository.class);
        tripService = new TripService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class),
                tripRepository,
                tripRegionRepository,
                executions,
                mock(RegionCatalog.class),
                tripAttachmentRepository,
                storageClient,
                mock(TripAccessService.class),
                objectMapper,
                mock(StoryRepository.class), mock(TripMapCacheStore.class),
                Clock.systemUTC(),
                mock(com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository.class)
        );
    }

    @Test
    void 여덟개를_조회하면_일곱개와_다음_커서를_반환한다() {
        List<Trip> trips = new ArrayList<>();
        for (long id = 8; id >= 1; id--) {
            trips.add(trip(id, false, ProcessingStatus.COMPLETED, null));
        }
        when(tripRepository.findListLatest(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(trips);

        var response = tripService.findTrips(1L, TripListRequest.from(null, null, null, objectMapper));

        assertThat(response.items()).extracting(item -> item.tripId())
                .containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L);
        assertThat(response.hasNext()).isTrue();
        TripListCursor cursor = TripListCursor.decode(response.nextCursor(), objectMapper);
        assertThat(cursor.tripId()).isEqualTo(2L);
        assertThat(cursor.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(cursor.favoriteGroup()).isFalse();
        verify(executions).findActiveTripIds(1L, List.of(8L, 7L, 6L, 5L, 4L, 3L, 2L));
        verifyNoMoreInteractions(executions);
    }

    @Test
    void 즐겨찾기_그룹을_먼저_채우고_남은_수만큼_일반_그룹을_조회한다() {
        List<Trip> favorites = List.of(
                trip(9L, true, ProcessingStatus.COMPLETED, null),
                trip(8L, true, ProcessingStatus.COMPLETED, null)
        );
        List<Trip> normals = List.of(
                trip(7L, false, ProcessingStatus.COMPLETED, null),
                trip(6L, false, ProcessingStatus.COMPLETED, null),
                trip(5L, false, ProcessingStatus.COMPLETED, null),
                trip(4L, false, ProcessingStatus.COMPLETED, null),
                trip(3L, false, ProcessingStatus.COMPLETED, null),
                trip(2L, false, ProcessingStatus.COMPLETED, null)
        );
        when(tripRepository.findFavoriteGroupLatest(
                eq(1L), eq(true), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(favorites);
        when(tripRepository.findFavoriteGroupLatest(
                eq(1L), eq(false), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(normals);

        var response = tripService.findTrips(1L, TripListRequest.from(null, "LATEST", "true", objectMapper));

        assertThat(response.items()).extracting(item -> item.tripId())
                .containsExactly(9L, 8L, 7L, 6L, 5L, 4L, 3L);
        assertThat(response.hasNext()).isTrue();
        assertThat(TripListCursor.decode(response.nextCursor(), objectMapper).favoriteGroup()).isFalse();
    }

    @Test
    void 일반_그룹_커서부터는_즐겨찾기_그룹을_다시_조회하지_않는다() {
        LocalDate startDate = LocalDate.of(2026, 9, 22);
        String cursor = new TripListCursor(
                TripSort.LATEST, true, false, startDate, 7L).encode(objectMapper);
        Trip nextNormal = trip(6L, false, ProcessingStatus.COMPLETED, null);
        when(tripRepository.findFavoriteGroupLatest(
                eq(1L), eq(false), eq(startDate), eq(7L), any(Pageable.class)))
                .thenReturn(List.of(nextNormal));

        var response = tripService.findTrips(
                1L, TripListRequest.from(cursor, "LATEST", "true", objectMapper));

        assertThat(response.items()).extracting(item -> item.tripId()).containsExactly(6L);
        verify(tripRepository, never()).findFavoriteGroupLatest(
                eq(1L), eq(true), any(), any(), any(Pageable.class));
    }

    @Test
    void 지역과_첨부수를_일괄_조회하고_완료된_여행의_썸네일_URL을_만든다() {
        Trip completed = trip(7L, true, ProcessingStatus.COMPLETED, "thumb/7.webp");
        Trip processing = trip(6L, false, ProcessingStatus.PROCESSING, "thumb/6.webp");
        when(tripRepository.findListOldest(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(completed, processing));
        when(tripRegionRepository.findNamesByTripIds(List.of(7L, 6L))).thenReturn(List.of(
                new TripRegionName(7L, "제주"),
                new TripRegionName(7L, "부산"),
                new TripRegionName(7L, "서울"),
                new TripRegionName(7L, "인천"),
                new TripRegionName(6L, "강릉")
        ));
        when(tripAttachmentRepository.countNotDeletedByTripIds(List.of(7L, 6L)))
                .thenReturn(List.of(new TripAttachmentCount(7L, 4L)));
        when(storageClient.createReadUrl("thumb/7.webp")).thenReturn("https://cdn.test/7");

        var response = tripService.findTrips(1L, TripListRequest.from(null, "OLDEST", "false", objectMapper));

        assertThat(response.items().getFirst().placeSummary()).isEqualTo("제주, 부산, 서울");
        assertThat(response.items().getFirst().attachmentCount()).isEqualTo(4L);
        assertThat(response.items().getFirst().thumbnailUrl()).isEqualTo("https://cdn.test/7");
        assertThat(response.items().getFirst().status()).isEqualTo(TripListItemResponse.Status.COMPLETED);
        assertThat(response.items().get(1).placeSummary()).isEqualTo("강릉");
        assertThat(response.items().get(1).attachmentCount()).isZero();
        assertThat(response.items().get(1).thumbnailUrl()).isNull();
        assertThat(response.items().get(1).status()).isEqualTo(TripListItemResponse.Status.PROCESSING);
        verify(storageClient, never()).createReadUrl("thumb/6.webp");
    }

    @Test
    void 조회_결과가_없으면_부가정보를_조회하지_않는다() {
        when(tripRepository.findListLatest(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        var response = tripService.findTrips(1L, TripListRequest.from(null, null, null, objectMapper));

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verifyNoInteractions(tripRegionRepository, tripAttachmentRepository, storageClient, executions);
    }

    @Test
    void 진행중인_추가_작업이_있는_완료_여행만_UPDATING으로_표시한다() {
        Trip updating = trip(7L, false, ProcessingStatus.COMPLETED, "thumb/7.webp");
        Trip completed = trip(6L, false, ProcessingStatus.COMPLETED, null);
        Trip processing = trip(5L, false, ProcessingStatus.PROCESSING, null);
        when(tripRepository.findListLatest(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(updating, completed, processing));
        when(executions.findActiveTripIds(1L, List.of(7L, 6L, 5L))).thenReturn(List.of(7L, 5L));
        when(storageClient.createReadUrl("thumb/7.webp")).thenReturn("https://cdn.test/7");

        var response = tripService.findTrips(1L, TripListRequest.from(null, null, null, objectMapper));

        assertThat(response.items()).extracting(TripListItemResponse::status).containsExactly(
                TripListItemResponse.Status.UPDATING,
                TripListItemResponse.Status.COMPLETED,
                TripListItemResponse.Status.PROCESSING);
        assertThat(response.items().getFirst().thumbnailUrl()).isEqualTo("https://cdn.test/7");
        assertThat(updating.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        verify(executions).findActiveTripIds(1L, List.of(7L, 6L, 5L));
        verifyNoMoreInteractions(executions);
    }

    private Trip trip(Long id, boolean favorite, ProcessingStatus status, String thumbnailKey) {
        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(id);
        when(trip.getTripName()).thenReturn("여행 " + id);
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 9, 2));
        when(trip.getFavorite()).thenReturn(favorite);
        when(trip.getProcessingStatus()).thenReturn(status);
        when(trip.getThumbnailKey()).thenReturn(thumbnailKey);
        return trip;
    }
}
