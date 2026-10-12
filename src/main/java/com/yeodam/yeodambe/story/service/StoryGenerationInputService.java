package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryGenerationInputService {
    private final StoryGenerationRequestValidator requestValidator;
    private final TripRepository tripRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAttachmentRepository tripAttachmentRepository;

    public Trip validate(
            Long userId,
            Long tripId,
            StoryGenerationRequest request
    ) {
        requestValidator.validate(request);

        Trip trip = tripRepository.findById(tripId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(TripNotFoundException::new);

        if (!trip.getUserId().equals(userId)) {
            throw new StoryGenerationForbiddenException();
        }

        List<TripDetailPlace> places =
                tripDetailPlaceRepository.findAllById(request.tripPlaceIds());

        boolean invalidPlaces =
                places.size() != request.tripPlaceIds().size()
                        || places.stream().anyMatch(place ->
                        !tripId.equals(place.getTripId())
                                || place.getDeletedAt() != null
                );

        if (invalidPlaces) {
            throw new InvalidStoryRequestException();
        }

        var attachmentCounts =
                tripAttachmentRepository.countActiveByTripPlaceIds(
                        request.tripPlaceIds()
                );

        if (attachmentCounts.size() != request.tripPlaceIds().size()) {
            throw new InvalidStoryRequestException();
        }

        return trip;
    }
}