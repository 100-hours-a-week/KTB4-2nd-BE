package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.TripListCursor;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripDetailResponse;
import com.yeodam.yeodambe.trip.service.response.TripFavoriteResponse;
import com.yeodam.yeodambe.trip.service.response.TripListItemResponse;
import com.yeodam.yeodambe.trip.service.response.TripListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;

import java.util.HashMap;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripService {
    private static final int TRIP_LIST_SIZE = 7;
    private static final int TRIP_LIST_FETCH_SIZE = TRIP_LIST_SIZE + 1;

    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final RegionCatalog regionCatalog;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final TripAccessService tripAccessService;

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

    @Transactional
    public TripFavoriteResponse registerFavorite(Long tripId, Long userId) {
        Trip trip = tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                        tripId, userId, ProcessingStatus.COMPLETED)
                .orElseThrow(TripNotFoundException::new);

        trip.changeFavorite(true);
        return new TripFavoriteResponse(trip.getId(), trip.getFavorite());
    }

    @Transactional(readOnly = true)
    public TripDetailResponse findTripDetail(Long tripId, Long userId) {
        Trip trip = tripAccessService.requireReadableTrip(tripId, userId);

        if (trip.getProcessingStatus() == ProcessingStatus.CANCELED) {
            throw new TripNotFoundException();
        }
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripDetailNotAvailableException();
        }

        List<TripDetailResponse.Region> regions = tripRegionRepository
                .findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(tripId).stream()
                .map(region -> new TripDetailResponse.Region(
                        region.getId(),
                        region.getRegionCode(),
                        region.getRegionName()
                ))
                .toList();

        return new TripDetailResponse(
                trip.getId(),
                trip.getTripName(),
                trip.getStartDate(),
                trip.getEndDate(),
                ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()),
                regions,
                tripAttachmentRepository.countActiveByTripId(tripId),
                false,
                trip.getFavorite()
        );
    }

    @Transactional
    public void removeFavorite(Long tripId, Long userId) {
        Trip trip = tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                        tripId, userId, ProcessingStatus.COMPLETED)
                .orElseThrow(TripNotFoundException::new);

        trip.changeFavorite(false);
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
                        createThumbnailUrl(trip),
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

    @Transactional(readOnly = true)
    public TripListResponse findTrips(Long userId, TripListRequest request) {
        List<Trip> fetched = findTripPage(userId, request);
        boolean hasNext = fetched.size() > TRIP_LIST_SIZE;
        List<Trip> page = hasNext ? fetched.subList(0, TRIP_LIST_SIZE) : fetched;

        if (page.isEmpty()) {
            return new TripListResponse(List.of(), false, null);
        }

        List<Long> tripIds = page.stream().map(Trip::getId).toList();
        Map<Long, List<String>> regionNames = tripRegionRepository.findNamesByTripIds(tripIds).stream()
                .collect(Collectors.groupingBy(
                        TripRegionName::tripId,
                        LinkedHashMap::new,
                        Collectors.mapping(TripRegionName::regionName, Collectors.toList())
                ));
        Map<Long, Long> attachmentCounts = tripAttachmentRepository
                .countNotDeletedByTripIds(tripIds).stream()
                .collect(Collectors.toMap(
                        TripAttachmentCount::tripId,
                        TripAttachmentCount::attachmentCount
                ));

        List<TripListItemResponse> items = page.stream()
                .map(trip -> new TripListItemResponse(
                        trip.getId(),
                        trip.getTripName(),
                        trip.getStartDate(),
                        trip.getEndDate(),
                        regionNames.getOrDefault(trip.getId(), List.of()).stream()
                                .limit(3)
                                .collect(Collectors.joining(", ")),
                        attachmentCounts.getOrDefault(trip.getId(), 0L),
                        trip.getFavorite(),
                        trip.getProcessingStatus() == ProcessingStatus.PROCESSING
                                ? null
                                : createThumbnailUrl(trip)
                ))
                .toList();

        String nextCursor = null;
        if (hasNext) {
            Trip last = page.getLast();
            nextCursor = new TripListCursor(
                    request.sort(),
                    request.favorite(),
                    last.getFavorite(),
                    last.getCreatedAt(),
                    last.getId()
            ).encode();
        }
        return new TripListResponse(items, hasNext, nextCursor);
    }

    private List<Trip> findTripPage(Long userId, TripListRequest request) {
        TripListCursor cursor = request.cursor();
        if (!request.favorite()) {
            return findGroup(userId, request.sort(), null, cursor, TRIP_LIST_FETCH_SIZE);
        }

        boolean favoriteGroup = cursor == null || cursor.favoriteGroup();
        if (!favoriteGroup) {
            return findGroup(userId, request.sort(), false, cursor, TRIP_LIST_FETCH_SIZE);
        }

        List<Trip> result = new ArrayList<>(
                findGroup(userId, request.sort(), true, cursor, TRIP_LIST_FETCH_SIZE)
        );
        if (result.size() < TRIP_LIST_FETCH_SIZE) {
            result.addAll(findGroup(
                    userId,
                    request.sort(),
                    false,
                    null,
                    TRIP_LIST_FETCH_SIZE - result.size()
            ));
        }
        return result;
    }

    private List<Trip> findGroup(
            Long userId,
            TripSort sort,
            Boolean favoriteGroup,
            TripListCursor cursor,
            int size
    ) {
        var page = PageRequest.of(0, size);
        var createdAt = cursor == null ? null : cursor.createdAt();
        var tripId = cursor == null ? null : cursor.tripId();

        if (favoriteGroup == null) {
            return sort == TripSort.LATEST
                    ? tripRepository.findListLatest(userId, createdAt, tripId, page)
                    : tripRepository.findListOldest(userId, createdAt, tripId, page);
        }
        return sort == TripSort.LATEST
                ? tripRepository.findFavoriteGroupLatest(
                        userId, favoriteGroup, createdAt, tripId, page)
                : tripRepository.findFavoriteGroupOldest(
                        userId, favoriteGroup, createdAt, tripId, page);
    }

    private String createThumbnailUrl(Trip trip) {
        String thumbnailKey = trip.getThumbnailKey();

        if (thumbnailKey == null || thumbnailKey.isBlank()) {
            return null;
        }

        return tripAttachmentStorageClient.createReadUrl(thumbnailKey);
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
