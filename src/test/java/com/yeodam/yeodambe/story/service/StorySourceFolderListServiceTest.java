package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.response.TripPlaceFolderListResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotAllowedException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorySourceFolderListServiceTest {
    private final TripPlaceFolderListService tripPlaceFolderListService =
            mock(TripPlaceFolderListService.class);
    private final TripAccessService tripAccessService = mock(TripAccessService.class);
    private final AdditionalAttachmentExecutionRepository additionalExecutions =
            mock(AdditionalAttachmentExecutionRepository.class);
    private final Trip trip = mock(Trip.class);
    private final StorySourceFolderListService service =
            new StorySourceFolderListService(tripPlaceFolderListService, tripAccessService, additionalExecutions);

    @BeforeEach
    void prepareCompletedTrip() {
        when(tripAccessService.requireOwnedTrip(7L, 1L)).thenReturn(trip);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
    }

    @Test
    void 폴더와_페이지정보를_유지하고_선택상태는_false로_반환한다() {
        when(tripPlaceFolderListService.findPlaceFolders(1L, 7L, "cursor"))
                .thenReturn(new TripPlaceFolderListResponse(
                        List.of(new TripPlaceFolderListResponse.Item(
                                101L, "성산일출봉", 42L, "https://storage.test/thumbnail")),
                        true,
                        "next-cursor"
                ));

        var response = service.findFolders(1L, 7L, "cursor");

        assertThat(response.items()).hasSize(1);
        var item = response.items().getFirst();
        assertThat(item.tripPlaceId()).isEqualTo(101L);
        assertThat(item.placeName()).isEqualTo("성산일출봉");
        assertThat(item.attachmentCount()).isEqualTo(42L);
        assertThat(item.thumbnailUrl()).isEqualTo("https://storage.test/thumbnail");
        assertThat(item.selected()).isFalse();
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("next-cursor");
        verify(tripPlaceFolderListService).findPlaceFolders(1L, 7L, "cursor");
    }

    @Test
    void 폴더가_없으면_빈목록과_마지막페이지를_반환한다() {
        when(tripPlaceFolderListService.findPlaceFolders(1L, 7L, null))
                .thenReturn(new TripPlaceFolderListResponse(List.of(), false, null));

        var response = service.findFolders(1L, 7L, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 여행접근실패를_빈목록으로_바꾸지_않는다() {
        when(tripAccessService.requireOwnedTrip(7L, 1L))
                .thenThrow(new TripNotFoundException());

        assertThatThrownBy(() -> service.findFolders(1L, 7L, null))
                .isInstanceOf(TripNotFoundException.class);
    }
    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 완료되지_않은_여행은_폴더조회_전에_거절한다(ProcessingStatus status) {
        when(trip.getProcessingStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.findFolders(1L, 7L, null))
                .isInstanceOf(StoryGenerationNotAllowedException.class);
        verifyNoInteractions(tripPlaceFolderListService, additionalExecutions);
    }

    @Test
    void 사진추가가_진행중이면_폴더조회_전에_거절한다() {
        when(additionalExecutions.existsByTripIdAndEndedAtIsNull(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.findFolders(1L, 7L, null))
                .isInstanceOf(StoryGenerationNotAllowedException.class);
        verifyNoInteractions(tripPlaceFolderListService);
    }

}
