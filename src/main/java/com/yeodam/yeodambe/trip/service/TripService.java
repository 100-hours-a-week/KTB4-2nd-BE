package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.common.exception.TripUpdateNotAllowedException;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
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
import org.slf4j.spi.LoggingEventBuilder;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.TripEditResponse;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
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
@Slf4j
public class TripService {
    private static final int TRIP_LIST_SIZE = 7;
    private static final int TRIP_LIST_FETCH_SIZE = TRIP_LIST_SIZE + 1;
    private static final int EDIT_ATTACHMENT_PAGE_SIZE = 18;

    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final RegionCatalog regionCatalog;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final TripAccessService tripAccessService;
    private final ObjectMapper objectMapper;

    @Transactional
    public TripCreateResponse createTrip(Long userId, TripCreateRequest request) {
        validateTrip(
                request.startDate(),
                request.endDate(),
                request.regionCodes()
        );

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
    public TripUpdateResponse updateTrip(
            Long tripId,
            Long userId,
            TripUpdateRequest request
    ) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripUpdateNotAllowedException();
        }

        List<TripRegion> existingRegions = tripRegionRepository
                .findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(tripId);

        String tripName = request.tripName() != null
                ? request.tripName() : trip.getTripName();
        LocalDate startDate = request.startDate() != null
                ? request.startDate() : trip.getStartDate();
        LocalDate endDate = request.endDate() != null
                ? request.endDate() : trip.getEndDate();
        List<String> regionCodes = request.regionCodes() != null
                ? request.regionCodes()
                : existingRegions.stream()
                .map(TripRegion::getRegionCode)
                .toList();

        validateTrip(startDate, endDate, regionCodes);

        List<RegionCatalog.Region> requestedRegions = regionCodes.stream()
                .map(regionCatalog::getRequired)
                .toList();

        if (tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNullAndIdNot(
                userId, tripName, tripId
        )) {
            throw new TripNameDuplicatedException();
        }

        trip.changeInformation(tripName, startDate, endDate);

        if (request.regionCodes() != null) {
            updateRegions(trip, existingRegions, requestedRegions);
        }

        List<TripUpdateResponse.Region> regions = tripRegionRepository
                .findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(tripId).stream()
                .map(region -> new TripUpdateResponse.Region(
                        region.getId(),
                        region.getRegionCode(),
                        region.getRegionName()
                ))
                .toList();

        return new TripUpdateResponse(
                trip.getId(),
                trip.getTripName(),
                trip.getStartDate(),
                trip.getEndDate(),
                regions
        );
    }

    @Transactional(readOnly = true)
    public TripEditResponse findTripForEdit(
            Long tripId,
            Long userId,
            String cursor
    ) {
        Trip trip = tripAccessService.requireReadableTrip(tripId, userId);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripUpdateNotAllowedException();
        }

        AttachmentCursor attachmentCursor = AttachmentCursor.decode(
                cursor, objectMapper
        );

        List<TripAttachment> attachments =
                tripAttachmentRepository.findForEditWithCursor(
                        tripId,
                        attachmentCursor == null
                                ? null : attachmentCursor.createdAt(),
                        attachmentCursor == null
                                ? null : attachmentCursor.tripAttachmentId(),
                        PageRequest.of(0, EDIT_ATTACHMENT_PAGE_SIZE + 1)
                );

        boolean hasNext = attachments.size() > EDIT_ATTACHMENT_PAGE_SIZE;

        List<TripAttachmentListResponse.Item> items = attachments.stream()
                .limit(EDIT_ATTACHMENT_PAGE_SIZE)
                .map(attachment -> new TripAttachmentListResponse.Item(
                        attachment.getId(),
                        tripAttachmentStorageClient.createReadUrl(
                                attachment.getPreviewStorageKey()
                        )
                ))
                .toList();

        String nextCursor = null;
        if (hasNext) {
            TripAttachment last = attachments.get(
                    EDIT_ATTACHMENT_PAGE_SIZE - 1
            );
            nextCursor = new AttachmentCursor(
                    last.getCreatedAt(), last.getId()
            ).encode(objectMapper);
        }

        List<TripEditResponse.Region> regions = tripRegionRepository
                .findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(tripId).stream()
                .map(region -> new TripEditResponse.Region(
                        region.getId(),
                        region.getRegionCode(),
                        region.getRegionName()
                ))
                .toList();

        return new TripEditResponse(
                trip.getId(),
                trip.getTripName(),
                trip.getStartDate(),
                trip.getEndDate(),
                regions,
                tripAttachmentRepository.countForEditByTripId(tripId),
                new TripAttachmentListResponse(items, hasNext, nextCursor)
        );
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
        long startedAt = System.nanoTime();

        log.atInfo()
                .addKeyValue("event", "trip_view")
                .addKeyValue("result", "started")
                .addKeyValue("trip_id", tripId)
                .log("여행 상세 조회를 시작했습니다.");

        try {
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

            long attachmentCount = tripAttachmentRepository.countActiveByTripId(tripId);

            TripDetailResponse response = new TripDetailResponse(
                    trip.getId(),
                    trip.getTripName(),
                    trip.getStartDate(),
                    trip.getEndDate(),
                    ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()),
                    regions,
                    attachmentCount,
                    false,
                    trip.getFavorite()
            );

            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;

            log.atInfo()
                    .addKeyValue("event", "trip_view")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("attachment_count", attachmentCount)
                    .addKeyValue("duration_ms", durationMillis)
                    .log("여행 상세 조회를 완료했습니다.");

            return response;
        } catch (RuntimeException failure) {
            ErrorMessage errorCode = ErrorMessage.INTERNAL_SERVER_ERROR;

            if (failure instanceof TripNotFoundException) {
                errorCode = ErrorMessage.TRIP_NOT_FOUND;
            } else if (failure instanceof TripDetailNotAvailableException) {
                errorCode = ErrorMessage.TRIP_DETAIL_NOT_AVAILABLE;
            }

            LoggingEventBuilder logEvent = errorCode == ErrorMessage.INTERNAL_SERVER_ERROR
                    ? log.atError()
                    : log.atWarn();

            logEvent
                    .addKeyValue("event", "trip_view")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("error_code", errorCode.name())
                    .log("여행 상세 조회에 실패했습니다.", failure);

            throw failure;
        }
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
                        trip.getProcessingStatus(),
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
                    last.getStartDate(),
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
        var startDate = cursor == null ? null : cursor.startDate();
        var tripId = cursor == null ? null : cursor.tripId();

        if (favoriteGroup == null) {
            return sort == TripSort.LATEST
                    ? tripRepository.findListLatest(userId, startDate, tripId, page)
                    : tripRepository.findListOldest(userId, startDate, tripId, page);
        }
        return sort == TripSort.LATEST
                ? tripRepository.findFavoriteGroupLatest(
                        userId, favoriteGroup, startDate, tripId, page)
                : tripRepository.findFavoriteGroupOldest(
                        userId, favoriteGroup, startDate, tripId, page);
    }

    private String createThumbnailUrl(Trip trip) {
        String thumbnailKey = trip.getThumbnailKey();

        if (thumbnailKey == null || thumbnailKey.isBlank()) {
            return null;
        }

        return tripAttachmentStorageClient.createReadUrl(thumbnailKey);
    }

    private void updateRegions(
            Trip trip,
            List<TripRegion> existingRegions,
            List<RegionCatalog.Region> requestedRegions
    ) {
        LocalDateTime deletedAt = LocalDateTime.now();

        for (TripRegion existing : existingRegions) {
            boolean retained = requestedRegions.stream()
                    .anyMatch(region ->
                            region.code().equals(existing.getRegionCode()));

            if (!retained) {
                existing.softDelete(deletedAt);
            }
        }

        List<TripRegion> addedRegions = requestedRegions.stream()
                .filter(region -> existingRegions.stream()
                        .noneMatch(existing ->
                                existing.getRegionCode().equals(region.code())))
                .map(region -> new TripRegion(
                        trip,
                        region.code(),
                        region.name(),
                        region.latitude(),
                        region.longitude()
                ))
                .toList();

        tripRegionRepository.saveAll(addedRegions);
    }

    private void validateTrip(
            LocalDate startDate,
            LocalDate endDate,
            List<String> regionCodes
    ) {
        LocalDate today = LocalDate.now();

        if (endDate.isBefore(startDate)
                || startDate.isAfter(today)
                || endDate.isAfter(today)
                || ChronoUnit.DAYS.between(startDate, endDate) + 1 > 92
                || new HashSet<>(regionCodes).size() != regionCodes.size()) {
            throw new InvalidTripRequestException();
        }
    }
}
