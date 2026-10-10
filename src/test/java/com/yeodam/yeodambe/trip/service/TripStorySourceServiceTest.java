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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TripStorySourceServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripDetailPlaceRepository folders = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripStorySourceService service = new TripStorySourceService(trips, folders, attachments);
    private final LocalDateTime takenAt = LocalDateTime.of(2026, 10, 10, 9, 0);

    @Test
    void preservesSelectedFolderOrderAndPhotoData() {
        Trip trip = ownedTrip();
        when(trip.getId()).thenReturn(100L);
        when(trip.getTripName()).thenReturn("여행");
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 10, 10));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 10, 11));
        List<Long> selected = List.of(20L, 10L);
        var selectedFolders = List.of(folder(10L), folder(20L));
        var selectedPhotos = List.of(photo(1L, 10L, 80), photo(2L, 10L, null), photo(3L, 20L, 90));
        when(folders.findAllById(selected)).thenReturn(selectedFolders);
        when(attachments.findForStoryGeneration(100L, selected)).thenReturn(selectedPhotos);

        TripStorySource result = service.read(2L, 100L, selected);

        assertThat(result.tripId()).isEqualTo(100L);
        assertThat(result.tripName()).isEqualTo("여행");
        assertThat(result.startDate()).isEqualTo(trip.getStartDate());
        assertThat(result.endDate()).isEqualTo(trip.getEndDate());
        assertThat(result.places()).extracting(TripStorySource.Place::tripPlaceId)
                .containsExactly(20L, 10L);
        assertThat(result.places().getFirst().placeName()).isEqualTo("장소20");
        assertThat(result.places().getFirst().photos()).containsExactly(
                new TripStorySource.Photo(3L, "analysis/3.jpg", takenAt, 90));
        assertThat(result.places().get(1).photos()).containsExactly(
                new TripStorySource.Photo(1L, "analysis/1.jpg", takenAt, 80),
                new TripStorySource.Photo(2L, "analysis/2.jpg", takenAt, null));
    }

    @Test
    void rejectsMissingTripBeforeReadingFolders() {
        when(trips.findById(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(2L, 100L, List.of(10L)))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(folders, attachments);
    }

    @Test
    void rejectsDeletedTripBeforeReadingFolders() {
        Trip trip = ownedTrip();
        when(trip.getDeletedAt()).thenReturn(takenAt);
        assertThatThrownBy(() -> service.read(2L, 100L, List.of(10L)))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(folders, attachments);
    }

    @Test
    void rejectsOtherOwnerBeforeReadingFolders() {
        Trip trip = ownedTrip();
        when(trip.getUserId()).thenReturn(3L);
        assertThatThrownBy(() -> service.read(2L, 100L, List.of(10L)))
                .isInstanceOf(StoryGenerationForbiddenException.class);
        verifyNoInteractions(folders, attachments);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingSelection(List<Long> selected) {
        ownedTrip();
        assertThatThrownBy(() -> service.read(2L, 100L, selected))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(folders, attachments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "otherTrip", "duplicate"})
    void rejectsInvalidFoldersBeforeReadingPhotos(String condition) {
        ownedTrip();
        List<Long> selected = condition.equals("duplicate") ? List.of(10L, 10L) : List.of(10L);
        TripDetailPlace folder = folder(10L);
        if (condition.equals("deleted")) when(folder.getDeletedAt()).thenReturn(takenAt);
        if (condition.equals("otherTrip")) when(folder.getTripId()).thenReturn(999L);
        when(folders.findAllById(selected))
                .thenReturn(condition.equals("missing") ? List.of() : List.of(folder));

        assertThatThrownBy(() -> service.read(2L, 100L, selected))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(attachments);
    }

    @Test
    void rejectsEntireRequestWhenOneSelectedFolderHasNoEligiblePhotos() {
        ownedTrip();
        List<Long> selected = List.of(10L, 20L);
        var selectedFolders = List.of(folder(10L), folder(20L));
        var selectedPhotos = List.of(photo(1L, 10L, 80));
        when(folders.findAllById(selected)).thenReturn(selectedFolders);
        when(attachments.findForStoryGeneration(100L, selected))
                .thenReturn(selectedPhotos);

        assertThatThrownBy(() -> service.read(2L, 100L, selected))
                .isInstanceOf(InvalidStoryRequestException.class);
    }

    private Trip ownedTrip() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(2L);
        when(trips.findById(100L)).thenReturn(Optional.of(trip));
        return trip;
    }

    private TripDetailPlace folder(Long id) {
        TripDetailPlace folder = mock(TripDetailPlace.class);
        when(folder.getId()).thenReturn(id);
        when(folder.getTripId()).thenReturn(100L);
        when(folder.getPlaceName()).thenReturn("장소" + id);
        return folder;
    }

    private TripAttachment photo(Long id, Long placeId, Integer evaluation) {
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getId()).thenReturn(id);
        when(photo.getTripPlaceId()).thenReturn(placeId);
        when(photo.getAnalyzeStorageKey()).thenReturn("analysis/" + id + ".jpg");
        when(photo.getTakenAt()).thenReturn(takenAt);
        when(photo.getEvaluation()).thenReturn(evaluation);
        return photo;
    }
}
