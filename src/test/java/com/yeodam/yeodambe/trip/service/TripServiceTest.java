package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TripServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private TripAttachmentStorageClient tripAttachmentStorageClient;
    private RegionCatalog regionCatalog;
    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        tripAttachmentStorageClient = mock(TripAttachmentStorageClient.class);
        regionCatalog = mock(RegionCatalog.class);
        tripService = new TripService(
                tripRepository,
                tripRegionRepository,
                regionCatalog,
                tripAttachmentRepository,
                tripAttachmentStorageClient,
                mock(TripAccessService.class)
        );
    }

    @Test
    void 종료일이_빠르거나_날짜가_미래면_거부한다() {
        LocalDate today = LocalDate.now();

        assertInvalid(today, today.minusDays(1), List.of("50110"));
        assertInvalid(today.plusDays(1), today.plusDays(1), List.of("50110"));
        assertInvalid(today, today.plusDays(1), List.of("50110"));
        verifyNoInteractions(tripRepository, tripRegionRepository);
    }

    @Test
    void 시작일을_포함해_구십삼일이면_거부한다() {
        assertInvalid(LocalDate.now().minusDays(92), LocalDate.now(), List.of("50110"));
    }

    @Test
    void 같은_지역을_두번_선택하면_거부한다() {
        assertInvalid(LocalDate.now(), LocalDate.now(), List.of("50110", "50110"));
    }

    @Test
    void 없는_지역_코드는_거부한다() {
        when(regionCatalog.getRequired("99999")).thenThrow(new InvalidTripRequestException());

        assertThrows(InvalidTripRequestException.class,
                () -> tripService.createTrip(1L, request(LocalDate.now(), LocalDate.now(), List.of("99999"))));
        verifyNoInteractions(tripRepository, tripRegionRepository);
    }

    @Test
    void 같은_사용자의_활성_여행명이_있으면_거부한다() {
        givenRegion();
        when(tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(1L, "여행"))
                .thenReturn(true);

        assertThrows(TripNameDuplicatedException.class,
                () -> tripService.createTrip(1L, request(LocalDate.now(), LocalDate.now(), List.of("50110"))));
        verify(tripRepository, never()).save(any());
    }

    @Test
    void 당일치기는_저장한다() {
        givenRegion();
        givenSavedTrip();

        var sameDay = tripService.createTrip(1L,
                request(LocalDate.now(), LocalDate.now(), List.of("50110")));

        assertThat(sameDay.tripId()).isEqualTo(7L);
        assertThat(sameDay.status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripRegionRepository).saveAll(anyList());
    }

    @Test
    void 시작일을_포함한_구십이일은_저장한다() {
        givenRegion();
        givenSavedTrip();

        var response = tripService.createTrip(1L,
                request(LocalDate.now().minusDays(91), LocalDate.now(), List.of("50110")));

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripRegionRepository).saveAll(anyList());
    }

    @Test
    void 완료된_소유_여행을_즐겨찾기로_등록한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        var response = tripService.registerFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isTrue();
        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.isFavorite()).isTrue();
    }

    @Test
    void 이미_즐겨찾기인_여행도_true로_유지한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "favorite", true);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        var response = tripService.registerFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isTrue();
        assertThat(response.isFavorite()).isTrue();
    }

    @Test
    void 등록할_수_있는_여행이_없으면_찾을_수_없음으로_처리한다() {
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class,
                () -> tripService.registerFavorite(7L, 1L));
    }

    @Test
    void 즐겨찾기_삭제를_반복해도_false로_유지한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "favorite", true);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        tripService.removeFavorite(7L, 1L);
        tripService.removeFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isFalse();
    }

    @Test
    void 삭제할_수_있는_여행이_없으면_찾을_수_없음으로_처리한다() {
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class,
                () -> tripService.removeFavorite(7L, 1L));
    }

    @Test
    void 같은_지역의_완료_여행을_하나의_마커로_묶는다() {
        Trip firstTrip = mapTrip(1L, "첫 여행");
        Trip secondTrip = mapTrip(2L, "두 번째 여행");
        TripRegion firstRegion = mapRegion(firstTrip);
        TripRegion secondRegion = mapRegion(secondTrip);
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(firstRegion, secondRegion));

        var response = tripService.findMap(1L);

        assertThat(response.markers()).hasSize(1);
        var marker = response.markers().getFirst();
        assertThat(marker.regionCode()).isEqualTo("50110");
        assertThat(marker.regionName()).isEqualTo("제주특별자치도 제주시");
        assertThat(marker.latitude()).isEqualByComparingTo("33.4996");
        assertThat(marker.longitude()).isEqualByComparingTo("126.5312");
        assertThat(marker.tripCount()).isEqualTo(2);
        assertThat(marker.trips())
                .extracting(trip -> trip.tripId())
                .containsExactly(1L, 2L);
    }

    @Test
    void 여행별_미삭제_첨부_개수를_반환한다() {
        Trip trip = mapTrip(1L, "제주 여행");
        TripRegion region = mapRegion(trip);
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(region));
        when(tripAttachmentRepository.countNotDeletedByTripIds(List.of(1L)))
                .thenReturn(List.of(new TripAttachmentCount(1L, 3L)));

        var response = tripService.findMap(1L);

        assertThat(response.markers().getFirst().trips().getFirst().attachmentCount())
                .isEqualTo(3L);
    }

    @Test
    void 썸네일_키가_있으면_조회_URL을_반환한다() {
        Trip trip = mapTrip(1L, "제주 여행");
        TripRegion region = mapRegion(trip);
        when(trip.getThumbnailKey()).thenReturn("trip-uploads/first/preview.webp");
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(region));
        when(tripAttachmentStorageClient.createReadUrl("trip-uploads/first/preview.webp"))
                .thenReturn("https://example.com/presigned-thumbnail");

        var response = tripService.findMap(1L);

        assertThat(response.markers().getFirst().trips().getFirst().thumbnailUrl())
                .isEqualTo("https://example.com/presigned-thumbnail");
    }

    @Test
    void 썸네일_키가_없으면_조회_URL을_만들지_않는다() {
        Trip trip = mapTrip(1L, "제주 여행");
        TripRegion region = mapRegion(trip);
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(region));

        var response = tripService.findMap(1L);

        assertThat(response.markers().getFirst().trips().getFirst().thumbnailUrl())
                .isNull();
        verifyNoInteractions(tripAttachmentStorageClient);
    }

    @Test
    void 같은_여행이_여러_지역에_연결되면_각_마커에_포함한다() {
        Trip trip = mapTrip(1L, "전국 여행");
        TripRegion jeju = mapRegion(
                trip,
                "50110",
                "제주특별자치도 제주시",
                "33.4996",
                "126.5312"
        );
        TripRegion busan = mapRegion(
                trip,
                "26110",
                "부산광역시 중구",
                "35.1060",
                "129.0323"
        );
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of(busan, jeju));

        var response = tripService.findMap(1L);

        assertThat(response.markers()).hasSize(2);
        assertThat(response.markers())
                .extracting(marker -> marker.regionCode())
                .containsExactly("26110", "50110");
        assertThat(response.markers())
                .allSatisfy(marker -> assertThat(marker.trips())
                        .extracting(summary -> summary.tripId())
                        .containsExactly(1L));
    }

    @Test
    void 표시할_여행이_없으면_첨부_개수를_조회하지_않는다() {
        when(tripRegionRepository.findAllForMap(1L, ProcessingStatus.COMPLETED))
                .thenReturn(List.of());

        var response = tripService.findMap(1L);

        assertThat(response.markers()).isEmpty();
        verifyNoInteractions(tripAttachmentRepository);
    }

    private void assertInvalid(LocalDate start, LocalDate end, List<String> codes) {
        assertThrows(InvalidTripRequestException.class,
                () -> tripService.createTrip(1L, request(start, end, codes)));
    }

    private TripCreateRequest request(LocalDate start, LocalDate end, List<String> codes) {
        return new TripCreateRequest("여행", start, end, codes);
    }

    private void givenRegion() {
        when(regionCatalog.getRequired("50110")).thenReturn(new RegionCatalog.Region(
                "50110", "제주특별자치도 제주시", new BigDecimal("33.5"), new BigDecimal("126.5")));
    }

    private void givenSavedTrip() {
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> {
            Trip trip = invocation.getArgument(0);
            ReflectionTestUtils.setField(trip, "id", 7L);
            return trip;
        });
    }

    private Trip mapTrip(Long tripId, String tripName) {
        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(tripId);
        when(trip.getTripName()).thenReturn(tripName);
        return trip;
    }

    private TripRegion mapRegion(Trip trip) {
        return mapRegion(
                trip,
                "50110",
                "제주특별자치도 제주시",
                "33.4996",
                "126.5312"
        );
    }

    private TripRegion mapRegion(
            Trip trip,
            String regionCode,
            String regionName,
            String latitude,
            String longitude
    ) {
        TripRegion region = mock(TripRegion.class);
        when(region.getTrip()).thenReturn(trip);
        when(region.getRegionCode()).thenReturn(regionCode);
        when(region.getRegionName()).thenReturn(regionName);
        when(region.getLatitude()).thenReturn(new BigDecimal(latitude));
        when(region.getLongitude()).thenReturn(new BigDecimal(longitude));
        return region;
    }
}
