package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryGenerationInProgressException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryGenerationJobService {

    private final StoryGenerationInputService inputService;
    private final StoryRepository storyRepository;
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationPlaceRepository placeRepository;

    @Transactional
    public StoryGenerationJob create(
            Long userId,
            Long tripId,
            StoryGenerationRequest request
    ) {
        Trip trip = inputService.validate(userId, tripId, request);

        if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
            throw new StoryAlreadyExistsException();
        }

        if (jobRepository.existsByTripIdAndStatusIn(
                trip.getId(),
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        )) {
            log.atInfo()
                    .addKeyValue("event", "story_generation_rejected")
                    .addKeyValue("result", "rejected")
                    .addKeyValue("trip_id", trip.getId())
                    .addKeyValue("error_code", "STORY_GENERATION_IN_PROGRESS")
                    .addKeyValue("failure_stage", "active_job_check")
                    .log("진행 중인 스토리 작업이 있어 생성 요청을 거부했습니다.");
            throw new StoryGenerationInProgressException();
        }

        StoryGenerationJob job;

        try {
            job = jobRepository.saveAndFlush(
                    new StoryGenerationJob(
                            UUID.randomUUID().toString(),
                            trip.getId(),
                            userId,
                            request.mood()
                    )
            );
        } catch (DataIntegrityViolationException e) {
            if (isActiveJobConflict(e)) {
                log.atInfo()
                        .addKeyValue("event", "story_generation_rejected")
                        .addKeyValue("result", "rejected")
                        .addKeyValue("trip_id", trip.getId())
                        .addKeyValue("error_code", "STORY_GENERATION_IN_PROGRESS")
                        .addKeyValue("failure_stage", "active_job_unique")
                        .setCause(e)
                        .log("활성 작업 UNIQUE 충돌로 생성 요청을 거부했습니다.");
                throw new StoryGenerationInProgressException(e);
            }

            throw e;
        }

        List<StoryGenerationPlace> selectedPlaces = new ArrayList<>();

        for (int index = 0; index < request.tripPlaceIds().size(); index++) {
            selectedPlaces.add(
                    new StoryGenerationPlace(
                            job.getId(),
                            request.tripPlaceIds().get(index),
                            index + 1
                    )
            );
        }

        placeRepository.saveAllAndFlush(selectedPlaces);

        return job;
    }

    private boolean isActiveJobConflict(Throwable error) {
        String expectedConstraint = "uk_story_generation_active_trip";

        for (Throwable cause = error;
             cause != null;
             cause = cause.getCause()) {

            if (cause instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();

                return expectedConstraint.equals(constraintName)
                        || (constraintName != null
                        && constraintName.endsWith("." + expectedConstraint));
            }
        }

        return false;
    }
}