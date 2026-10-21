package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryGenerationNotAllowedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.story.service.response.StorySourceFolderListResponse;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.response.TripPlaceFolderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorySourceFolderListService {
    private final TripPlaceFolderListService tripPlaceFolderListService;
    private final TripAccessService tripAccessService;
    private final AdditionalAttachmentExecutionRepository additionalExecutionRepository;

    public StorySourceFolderListResponse findFolders(Long userId, Long tripId, String cursor) {
        var trip = tripAccessService.requireReadableTrip(tripId, userId);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED
                || additionalExecutionRepository.existsByTripIdAndEndedAtIsNull(tripId)) {
            throw new StoryGenerationNotAllowedException();
        }
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
