package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.common.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.PlaceFolderAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryGenerationInputServiceTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripDetailPlaceRepository tripDetailPlaceRepository = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository tripAttachmentRepository = mock(TripAttachmentRepository.class);
    private final StoryGenerationInputService service = new StoryGenerationInputService(
            new StoryGenerationRequestValidator(factory.getValidator()), tripRepository,
            tripDetailPlaceRepository, tripAttachmentRepository);
    private final StoryGenerationRequest request =
            new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(101L));

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void returnsOwnedActiveTrip() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(2L);
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        var folders = List.of(place(10L, null));
        when(tripDetailPlaceRepository.findAllById(List.of(101L)))
                .thenReturn(folders);
        when(tripAttachmentRepository.countActiveByTripPlaceIds(List.of(101L)))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(101L, 1L)));

        assertThat(service.validate(2L, 10L, request)).isSameAs(trip);
    }

    @Test
    void rejectsMissingTrip() {
        when(tripRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void rejectsDeletedTripBeforeOwnershipCheck() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(3L);
        when(trip.getDeletedAt()).thenReturn(LocalDateTime.of(2026, 10, 9, 9, 0));
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void rejectsOtherUsersActiveTrip() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(3L);
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(StoryGenerationForbiddenException.class);
    }

    @Test
    void rejectsInvalidRequestBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.validate(2L, 10L,
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L, 101L))))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripRepository, tripDetailPlaceRepository, tripAttachmentRepository);
    }

    @Test
    void acceptsMultipleFoldersRegardlessOfQueryResultOrder() {
        Trip trip = ownedTrip();
        var selected = new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L, 102L));
        var folders = List.of(place(10L, null), place(10L, null));
        when(tripDetailPlaceRepository.findAllById(selected.tripPlaceIds()))
                .thenReturn(folders);
        when(tripAttachmentRepository.countActiveByTripPlaceIds(selected.tripPlaceIds()))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(102L, 3L),
                        new PlaceFolderAttachmentCount(101L, 2L)));

        assertThat(service.validate(2L, 10L, selected)).isSameAs(trip);
    }

    @Test
    void rejectsMissingSelectedFolderBeforeCountingPhotos() {
        ownedTrip();
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds())).thenReturn(List.of());

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void rejectsFolderFromAnotherTripBeforeCountingPhotos() {
        ownedTrip();
        var folders = List.of(place(20L, null));
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds()))
                .thenReturn(folders);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void rejectsDeletedFolderBeforeCountingPhotos() {
        ownedTrip();
        var folders = List.of(place(10L, LocalDateTime.of(2026, 10, 9, 9, 0)));
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds()))
                .thenReturn(folders);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void rejectsSelectionWhenOneFolderHasNoActivePhotos() {
        ownedTrip();
        var selected = new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L, 102L));
        var folders = List.of(place(10L, null), place(10L, null));
        when(tripDetailPlaceRepository.findAllById(selected.tripPlaceIds()))
                .thenReturn(folders);
        when(tripAttachmentRepository.countActiveByTripPlaceIds(selected.tripPlaceIds()))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(101L, 2L)));

        assertThatThrownBy(() -> service.validate(2L, 10L, selected))
                .isInstanceOf(InvalidStoryRequestException.class);
    }

    private Trip ownedTrip() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(2L);
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        return trip;
    }

    private TripDetailPlace place(Long tripId, LocalDateTime deletedAt) {
        TripDetailPlace place = mock(TripDetailPlace.class);
        when(place.getTripId()).thenReturn(tripId);
        when(place.getDeletedAt()).thenReturn(deletedAt);
        return place;
    }
}
