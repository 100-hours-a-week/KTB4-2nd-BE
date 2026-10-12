package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.common.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripStorySource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripStorySourceService {
    private final TripRepository tripRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAttachmentRepository tripAttachmentRepository;

    public TripStorySource read(
            Long userId,
            Long tripId,
            List<Long> selectedPlaceIds
    ) {
        Trip trip = tripRepository.findById(tripId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(TripNotFoundException::new);

        if (!trip.getUserId().equals(userId)) {
            throw new StoryGenerationForbiddenException();
        }

        if (selectedPlaceIds == null || selectedPlaceIds.isEmpty()) {
            throw new InvalidStoryRequestException();
        }

        List<TripDetailPlace> folders =
                tripDetailPlaceRepository.findAllById(selectedPlaceIds);

        boolean invalidFolders =
                folders.size() != selectedPlaceIds.size()
                        || folders.stream().anyMatch(folder ->
                        !tripId.equals(folder.getTripId())
                                || folder.getDeletedAt() != null
                );

        if (invalidFolders) {
            throw new InvalidStoryRequestException();
        }

        Map<Long, TripDetailPlace> foldersById = folders.stream()
                .collect(Collectors.toMap(TripDetailPlace::getId, folder -> folder));

        Map<Long, List<TripAttachment>> photosByPlace =
                tripAttachmentRepository.findForStoryGeneration(
                        tripId, selectedPlaceIds
                ).stream().collect(Collectors.groupingBy(
                        TripAttachment::getTripPlaceId
                ));

        List<TripStorySource.Place> places = new ArrayList<>();

        for (Long placeId : selectedPlaceIds) {
            TripDetailPlace folder = foldersById.get(placeId);
            List<TripAttachment> photos =
                    photosByPlace.getOrDefault(placeId, List.of());

            if (photos.isEmpty()) {
                throw new InvalidStoryRequestException();
            }

            List<TripStorySource.Photo> sourcePhotos = photos.stream()
                    .map(photo -> new TripStorySource.Photo(
                            photo.getId(),
                            photo.getAnalyzeStorageKey(),
                            photo.getTakenAt(),
                            photo.getEvaluation()
                    ))
                    .toList();

            places.add(new TripStorySource.Place(
                    folder.getId(),
                    folder.getPlaceName(),
                    sourcePhotos
            ));
        }

        return new TripStorySource(
                trip.getId(),
                trip.getTripName(),
                trip.getStartDate(),
                trip.getEndDate(),
                places
        );
    }
}