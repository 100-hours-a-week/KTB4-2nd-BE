package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotAllowedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    private final AdditionalAttachmentExecutionRepository additionalExecutions = mock(AdditionalAttachmentExecutionRepository.class);
    private final StoryGenerationInputService service = new StoryGenerationInputService(
            new StoryGenerationRequestValidator(factory.getValidator()), tripRepository,
            tripDetailPlaceRepository, tripAttachmentRepository, additionalExecutions);
    private final StoryGenerationRequest request =
            new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L));

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 소유한_활성_여행을_반환한다() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(2L);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(tripRepository.findActiveForUpdate(10L)).thenReturn(Optional.of(trip));
        var folders = List.of(place(10L, null));
        when(tripDetailPlaceRepository.findAllById(List.of(101L)))
                .thenReturn(folders);
        when(tripAttachmentRepository.countActiveByTripPlaceIds(List.of(101L)))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(101L, 1L)));

        assertThat(service.validate(2L, 10L, request)).isSameAs(trip);
    }

    @Test
    void 없는_여행을_거부한다() {
        when(tripRepository.findActiveForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 삭제된_여행은_소유권_확인_전에_거부한다() {
        when(tripRepository.findActiveForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 다른_사용자의_활성_여행을_거부한다() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(3L);
        when(tripRepository.findActiveForUpdate(10L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(StoryGenerationForbiddenException.class);
    }

    @Test
    void 잘못된_요청은_DB_조회_전에_거부한다() {
        assertThatThrownBy(() -> service.validate(2L, 10L,
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L, 101L))))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripRepository, tripDetailPlaceRepository, tripAttachmentRepository, additionalExecutions);
    }

    @Test
    void 조회_결과_순서와_관계없이_여러_폴더를_허용한다() {
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
    void 선택한_폴더가_없으면_사진_수_조회_전에_거부한다() {
        ownedTrip();
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds())).thenReturn(List.of());

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 다른_여행의_폴더는_사진_수_조회_전에_거부한다() {
        ownedTrip();
        var folders = List.of(place(20L, null));
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds()))
                .thenReturn(folders);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 삭제된_폴더는_사진_수_조회_전에_거부한다() {
        ownedTrip();
        var folders = List.of(place(10L, LocalDateTime.of(2026, 10, 9, 9, 0)));
        when(tripDetailPlaceRepository.findAllById(request.tripPlaceIds()))
                .thenReturn(folders);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(InvalidStoryRequestException.class);
        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 한_폴더라도_활성_사진이_없으면_선택을_거부한다() {
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

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 처리가_완료되지_않은_여행을_거부한다(ProcessingStatus status) {
        Trip trip = ownedTrip();
        when(trip.getProcessingStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(StoryGenerationNotAllowedException.class);
        verifyNoInteractions(additionalExecutions, tripDetailPlaceRepository, tripAttachmentRepository);
    }

    @Test
    void 여행_처리가_완료되어도_미완료_사진_추가가_있으면_거부한다() {
        ownedTrip();
        when(additionalExecutions.existsByTripIdAndEndedAtIsNull(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.validate(2L, 10L, request))
                .isInstanceOf(StoryGenerationNotAllowedException.class);
        verifyNoInteractions(tripDetailPlaceRepository, tripAttachmentRepository);
    }

    private Trip ownedTrip() {
        Trip trip = mock(Trip.class);
        when(trip.getUserId()).thenReturn(2L);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(tripRepository.findActiveForUpdate(10L)).thenReturn(Optional.of(trip));
        return trip;
    }

    private TripDetailPlace place(Long tripId, LocalDateTime deletedAt) {
        TripDetailPlace place = mock(TripDetailPlace.class);
        when(place.getTripId()).thenReturn(tripId);
        when(place.getDeletedAt()).thenReturn(deletedAt);
        return place;
    }
}
