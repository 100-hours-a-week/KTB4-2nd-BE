package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripListCursor;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripListServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private TripAttachmentStorageClient storageClient;
    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        storageClient = mock(TripAttachmentStorageClient.class);
        tripService = new TripService(
                tripRepository,
                tripRegionRepository,
                mock(RegionCatalog.class),
                tripAttachmentRepository,
                storageClient
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

        var response = tripService.findTrips(1L, TripListRequest.from(null, null, null));

        assertThat(response.items()).extracting(item -> item.tripId())
                .containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L);
        assertThat(response.hasNext()).isTrue();
        TripListCursor cursor = TripListCursor.decode(response.nextCursor());
        assertThat(cursor.tripId()).isEqualTo(2L);
        assertThat(cursor.favoriteGroup()).isFalse();
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

        var response = tripService.findTrips(1L, TripListRequest.from(null, "LATEST", "true"));

        assertThat(response.items()).extracting(item -> item.tripId())
                .containsExactly(9L, 8L, 7L, 6L, 5L, 4L, 3L);
        assertThat(response.hasNext()).isTrue();
        assertThat(TripListCursor.decode(response.nextCursor()).favoriteGroup()).isFalse();
    }

    @Test
    void 일반_그룹_커서부터는_즐겨찾기_그룹을_다시_조회하지_않는다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 22, 0, 0, 7);
        String cursor = new TripListCursor(
                TripSort.LATEST, true, false, createdAt, 7L).encode();
        Trip nextNormal = trip(6L, false, ProcessingStatus.COMPLETED, null);
        when(tripRepository.findFavoriteGroupLatest(
                eq(1L), eq(false), eq(createdAt), eq(7L), any(Pageable.class)))
                .thenReturn(List.of(nextNormal));

        var response = tripService.findTrips(
                1L, TripListRequest.from(cursor, "LATEST", "true"));

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

        var response = tripService.findTrips(1L, TripListRequest.from(null, "OLDEST", "false"));

        assertThat(response.items().getFirst().placeSummary()).isEqualTo("제주, 부산, 서울");
        assertThat(response.items().getFirst().attachmentCount()).isEqualTo(4L);
        assertThat(response.items().getFirst().thumbnailUrl()).isEqualTo("https://cdn.test/7");
        assertThat(response.items().get(1).placeSummary()).isEqualTo("강릉");
        assertThat(response.items().get(1).attachmentCount()).isZero();
        assertThat(response.items().get(1).thumbnailUrl()).isNull();
        verify(storageClient, never()).createReadUrl("thumb/6.webp");
    }

    @Test
    void 조회_결과가_없으면_부가정보를_조회하지_않는다() {
        when(tripRepository.findListLatest(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        var response = tripService.findTrips(1L, TripListRequest.from(null, null, null));

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verifyNoInteractions(tripRegionRepository, tripAttachmentRepository, storageClient);
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
        when(trip.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 9, 22, 0, 0).plusSeconds(id));
        return trip;
    }
}
