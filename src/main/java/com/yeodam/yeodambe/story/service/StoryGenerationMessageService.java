package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
import com.yeodam.yeodambe.trip.service.TripStorySourceService;
import com.yeodam.yeodambe.trip.service.response.TripStorySource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryGenerationMessageService {

    private final StoryGenerationPlaceRepository placeRepository;
    private final TripStorySourceService tripStorySourceService;

    public StoryGenerationMessage assemble(StoryGenerationJob job) {
        List<Long> selectedPlaceIds = placeRepository
                .findAllByGenerationIdOrderByOrderNumberAsc(job.getId())
                .stream()
                .map(StoryGenerationPlace::getTripPlaceId)
                .toList();

        TripStorySource source = tripStorySourceService.read(
                job.getUserId(),
                job.getTripId(),
                selectedPlaceIds
        );

        List<StoryGenerationMessage.Place> places = source.places()
                .stream()
                .map(place -> new StoryGenerationMessage.Place(
                        place.tripPlaceId(),
                        place.placeName(),
                        place.photos().stream()
                                .map(photo -> new StoryGenerationMessage.Attachment(
                                        photo.tripAttachmentId(),
                                        photo.analyzeStorageKey(),
                                        photo.takenAt().atOffset(
                                                ZoneOffset.ofHours(9)
                                        ),
                                        photo.evaluation()
                                ))
                                .toList()
                ))
                .toList();

        return new StoryGenerationMessage(
                source.tripId(),
                job.getExecutionId(),
                source.tripName(),
                new StoryGenerationMessage.Period(
                        source.startDate(),
                        source.endDate()
                ),
                job.getMood(),
                places
        );
    }
}