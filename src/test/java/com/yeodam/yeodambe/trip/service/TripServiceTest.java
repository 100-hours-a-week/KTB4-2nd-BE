package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TripServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private RegionCatalog regionCatalog;
    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        regionCatalog = mock(RegionCatalog.class);
        tripService = new TripService(tripRepository, tripRegionRepository, regionCatalog);
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
}
