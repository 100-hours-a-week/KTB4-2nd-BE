package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.PlaceFolderAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.request.PlaceFolderCursor;
import com.yeodam.yeodambe.trip.service.response.TripPlaceFolderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripPlaceFolderListService {
    private static final int PAGE_SIZE = 6;

    private final TripAccessService tripAccessService;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final ObjectMapper objectMapper;

    public TripPlaceFolderListResponse findPlaceFolders(Long userId, Long tripId, String cursor) {
        tripAccessService.requireReadableTrip(tripId, userId);
        PlaceFolderCursor decoded = PlaceFolderCursor.decode(cursor, tripId, objectMapper);

        List<TripDetailPlace> fetched = tripDetailPlaceRepository.findPlaceFoldersWithCursor(
                tripId,
                decoded == null ? null : decoded.placeName(),
                decoded == null ? null : decoded.tripPlaceId(),
                PageRequest.of(0, PAGE_SIZE + 1)
        );
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<TripDetailPlace> page = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;

        if (page.isEmpty()) {
            return new TripPlaceFolderListResponse(List.of(), false, null);
        }

        List<Long> placeIds = page.stream()
                .map(TripDetailPlace::getId)
                .toList();
        Map<Long, Long> attachmentCounts = tripAttachmentRepository
                .countActiveByTripPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(
                        PlaceFolderAttachmentCount::tripPlaceId,
                        PlaceFolderAttachmentCount::attachmentCount
                ));
        List<TripPlaceFolderListResponse.Item> items = page.stream()
                .map(place -> new TripPlaceFolderListResponse.Item(
                        place.getId(),
                        place.getPlaceName(),
                        attachmentCounts.getOrDefault(place.getId(), 0L),
                        createThumbnailUrl(place.getThumbnailKey())
                ))
                .toList();

        String nextCursor = null;
        if (hasNext) {
            TripDetailPlace last = page.getLast();
            nextCursor = new PlaceFolderCursor(tripId, last.getPlaceName(), last.getId())
                    .encode(objectMapper);
        }
        return new TripPlaceFolderListResponse(items, hasNext, nextCursor);
    }

    private String createThumbnailUrl(String thumbnailKey) {
        return thumbnailKey == null || thumbnailKey.isBlank()
                ? null
                : tripAttachmentStorageClient.createReadUrl(thumbnailKey);
    }
}
