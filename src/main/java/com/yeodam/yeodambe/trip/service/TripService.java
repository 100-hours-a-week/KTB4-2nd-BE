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
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;

import java.util.HashMap;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TripService {
    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final RegionCatalog regionCatalog;
    private final TripAttachmentRepository tripAttachmentRepository;

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

    @Transactional(readOnly = true)
    public TripMapResponse findMap(Long userId) {
        List<TripRegion> regions = tripRegionRepository.findAllForMap(
                userId,
                ProcessingStatus.COMPLETED
        );

        if (regions.isEmpty()) {
            return new TripMapResponse(List.of());
        }

        List<Long> tripIds = regions.stream()
                .map(region -> region.getTrip().getId())
                .distinct()
                .toList();

        List<TripAttachmentCount> attachmentCountResults =
                tripAttachmentRepository.countNotDeletedByTripIds(tripIds);

        Map<Long, Long> attachmentCounts = new HashMap<>();

        for (TripAttachmentCount result : attachmentCountResults) {
            attachmentCounts.put(
                    result.tripId(),
                    result.attachmentCount()
            );
        }

        Map<String, List<TripRegion>> regionsByCode = new LinkedHashMap<>();

        for (TripRegion region : regions) {
            String regionCode = region.getRegionCode();

            if (!regionsByCode.containsKey(regionCode)) {
                regionsByCode.put(regionCode, new ArrayList<>());
            }

            List<TripRegion> groupedRegions = regionsByCode.get(regionCode);
            groupedRegions.add(region);
        }

        List<TripMapResponse.Marker> markers = new ArrayList<>();

        for (List<TripRegion> groupedRegions : regionsByCode.values()) {
            TripRegion representativeRegion = groupedRegions.getFirst();

            List<TripMapResponse.TripSummary> trips = new ArrayList<>();

            for (TripRegion region : groupedRegions) {
                Trip trip = region.getTrip();

                trips.add(new TripMapResponse.TripSummary(
                        trip.getId(),
                        trip.getTripName(),
                        null,
                        attachmentCounts.getOrDefault(trip.getId(), 0L)
                ));
            }

            markers.add(new TripMapResponse.Marker(
                    representativeRegion.getRegionCode(),
                    representativeRegion.getRegionName(),
                    representativeRegion.getLatitude(),
                    representativeRegion.getLongitude(),
                    trips.size(),
                    trips
            ));
        }

        return new TripMapResponse(markers);
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
