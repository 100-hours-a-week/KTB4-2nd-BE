package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.service.response.StorySourceFolderListResponse;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.response.TripPlaceFolderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StorySourceFolderListService {
    private final TripPlaceFolderListService tripPlaceFolderListService;

    public StorySourceFolderListResponse findFolders(Long userId, Long tripId, String cursor) {
        TripPlaceFolderListResponse folders =
                tripPlaceFolderListService.findPlaceFolders(userId, tripId, cursor);

        List<StorySourceFolderListResponse.Item> items = folders.items()
                .stream()
                .map(folder -> new StorySourceFolderListResponse.Item(
                        folder.tripPlaceId(),
                        folder.placeName(),
                        folder.attachmentCount(),
                        folder.thumbnailUrl(),
                        false
                ))
                .toList();

        return new StorySourceFolderListResponse(
                items,
                folders.hasNext(),
                folders.nextCursor()
        );
    }
}
