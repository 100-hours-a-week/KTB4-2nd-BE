package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryGenerationInputService {
    private final StoryGenerationRequestValidator requestValidator;
    private final TripRepository tripRepository;

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

        return trip;
    }
}