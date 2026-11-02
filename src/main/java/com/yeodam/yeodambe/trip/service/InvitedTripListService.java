package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.InvitedTripCursor;
import com.yeodam.yeodambe.trip.service.response.InvitedTripListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitedTripListService {
    private static final int PAGE_SIZE = 7;
    private static final ZoneId SHARE_ZONE = ZoneId.of("Asia/Seoul");

    private final TripShareUserRepository shareRepository;
    private final TripRegionRepository tripRegionRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public InvitedTripListResponse findInvitedTrips(Long userId, String cursor) {
        InvitedTripCursor decodedCursor = InvitedTripCursor.decode(cursor, objectMapper);
        LocalDateTime checkedAt = LocalDateTime.ofInstant(clock.instant(), SHARE_ZONE);
        long totalCount = shareRepository.countInvitedTrips(userId, checkedAt);

        List<TripShareUser> fetchedShares = shareRepository.findInvitedPage(
                userId, checkedAt,
                decodedCursor == null ? null : decodedCursor.startDate(),
                decodedCursor == null ? null : decodedCursor.tripId(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasNext = fetchedShares.size() > PAGE_SIZE;
        List<TripShareUser> pageShares = hasNext ? fetchedShares.subList(0, PAGE_SIZE) : fetchedShares;
        if (pageShares.isEmpty()) {
            return new InvitedTripListResponse(totalCount, List.of(), false, null);
        }

        List<Long> tripIds = pageShares.stream().map(TripShareUser::getTripId).toList();
        Map<Long, List<String>> regionNamesByTripId = loadRegionNames(tripIds);
        Map<Long, Long> attachmentCountsByTripId = loadAttachmentCounts(tripIds);
        List<InvitedTripListResponse.Item> items = pageShares.stream()
                .map((TripShareUser share) -> toItem(share, regionNamesByTripId, attachmentCountsByTripId))
                .toList();

        Trip lastTrip = pageShares.getLast().getTrip();
        String nextCursor = hasNext
                ? new InvitedTripCursor(lastTrip.getStartDate(), lastTrip.getId()).encode(objectMapper)
                : null;
        return new InvitedTripListResponse(totalCount, items, hasNext, nextCursor);
    }

    private Map<Long, List<String>> loadRegionNames(List<Long> tripIds) {
        return tripRegionRepository.findNamesByTripIds(tripIds).stream()
                .collect(Collectors.groupingBy(
                        TripRegionName::tripId,
                        Collectors.mapping(TripRegionName::regionName, Collectors.toList())
                ));
    }

    private Map<Long, Long> loadAttachmentCounts(List<Long> tripIds) {
        return tripAttachmentRepository.countNotDeletedByTripIds(tripIds).stream()
                .collect(Collectors.toMap(TripAttachmentCount::tripId, TripAttachmentCount::attachmentCount));
    }

    private InvitedTripListResponse.Item toItem(
            TripShareUser share,
            Map<Long, List<String>> regionNamesByTripId,
            Map<Long, Long> attachmentCountsByTripId
    ) {
        Trip trip = share.getTrip();
        String placeSummary = regionNamesByTripId.getOrDefault(trip.getId(), List.of()).stream()
                .limit(3)
                .collect(Collectors.joining(", "));
        return new InvitedTripListResponse.Item(
                trip.getId(), trip.getTripName(), trip.getStartDate(), trip.getEndDate(), placeSummary,
                attachmentCountsByTripId.getOrDefault(trip.getId(), 0L),
                createThumbnailUrl(trip.getThumbnailKey()), share.getPermission());
    }

    private String createThumbnailUrl(String thumbnailKey) {
        return thumbnailKey == null || thumbnailKey.isBlank()
                ? null
                : tripAttachmentStorageClient.createReadUrl(thumbnailKey);
    }
}
