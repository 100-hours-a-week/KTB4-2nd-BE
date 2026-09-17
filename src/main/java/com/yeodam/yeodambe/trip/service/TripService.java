package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripService {
    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final RegionCatalog regionCatalog;

    @Transactional
    public TripCreateResponse createTrip(Long userId, TripCreateRequest request) {
        validateTrip(request);

        List<RegionCatalog.Region> regions = request.regionCodes().stream()
                .map(regionCatalog::getRequired)
                .toList();

        if (tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(
                userId, request.tripName()
        )) {
            throw new TripNameDuplicatedException();
        }

        Trip trip = tripRepository.save(new Trip(
                userId,
                request.tripName(),
                request.startDate(),
                request.endDate()
        ));

        List<TripRegion> tripRegions = regions.stream()
                .map(region -> new TripRegion(
                        trip,
                        region.code(),
                        region.name(),
                        region.latitude(),
                        region.longitude()
                ))
                .toList();

        tripRegionRepository.saveAll(tripRegions);
        return new TripCreateResponse(trip.getId(), ProcessingStatus.PROCESSING);
    }

    private void validateTrip(TripCreateRequest request) {
        LocalDate today = LocalDate.now();

        if (request.endDate().isBefore(request.startDate())
                || request.startDate().isAfter(today)
                || request.endDate().isAfter(today)
                || ChronoUnit.DAYS.between(
                request.startDate(), request.endDate()) + 1 > 92
                || new HashSet<>(request.regionCodes()).size() != request.regionCodes().size()) {
            throw new InvalidTripRequestException();
        }
    }
}
