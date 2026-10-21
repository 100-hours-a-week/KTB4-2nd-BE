package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.TripSearchCondition;
import com.yeodam.yeodambe.trip.service.response.TripSearchCandidateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripSearchServiceTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripService service = new TripService(mock(AdditionalAttachmentModificationGuard.class), mock(TripRepository.class), regions,
            mock(com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository.class), mock(RegionCatalog.class), attachments, storage, mock(TripAccessService.class),
            new tools.jackson.databind.ObjectMapper(), mock(StoryRepository.class), mock(TripMapCacheStore.class), mock(TripMapCacheLock.class));

    @Test
    void 사진_ID가_없으면_DB_조회와_URL_생성을_생략한다() {
        assertThat(service.findSearchResults(1L, List.of()).attachments()).isEmpty();
        assertThat(service.findSearchResults(1L, List.of()).folders()).isEmpty();
        verifyNoInteractions(attachments, regions, storage);
    }

    @Test
    void 종료일_다음날을_제외하고_지역_목록이_비어있으면_지역_필터를_생략한다() {
        TripSearchCandidateResponse candidate = new TripSearchCandidateResponse(
                7L, "analyze", null, null, "여행");
        when(attachments.findSearchCandidates(1L, LocalDate.of(2024, 2, 29).atStartOfDay(),
                LocalDate.of(2024, 3, 1).atStartOfDay(), false, List.of(""))).thenReturn(List.of(candidate));
        assertThat(service.findSearchCandidates(1L, new TripSearchCondition(
                LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29), List.of())))
                .extracting(TripSearchCandidateResponse::tripAttachmentId)
                .containsExactly(7L);
    }

    @Test
    void 결과에_장소_썸네일과_장소_전체의_활성_사진_개수를_사용한다() {
        Trip trip = Trip.localMock(1L, "제주 여행", LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1), "trip-thumbnail");
        ReflectionTestUtils.setField(trip, "id", 9L);
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getId()).thenReturn(7L);
        when(photo.getTripId()).thenReturn(9L);
        when(photo.getTrip()).thenReturn(trip);
        when(photo.getTripPlaceId()).thenReturn(11L);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(9L, 1, "해변", null, null,
                null, null, "place-thumbnail");
        ReflectionTestUtils.setField(place, "id", 11L);
        when(photo.getTripPlace()).thenReturn(place);
        when(photo.getPreviewStorageKey()).thenReturn("photo-preview");
        when(attachments.findSearchResults(1L, List.of(7L))).thenReturn(List.of(photo));
        when(attachments.countActiveByTripPlaceIds(Set.of(11L)))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(11L, 50L)));
        when(storage.createReadUrl("place-thumbnail")).thenReturn("place-url");
        when(storage.createReadUrl("photo-preview")).thenReturn("photo-url");
        var result = service.findSearchResults(1L, List.of(7L));
        assertThat(result.attachments().getFirst().tripName()).isEqualTo("제주 여행");
        assertThat(result.attachments().getFirst().thumbnailUrl()).isEqualTo("photo-url");
        assertThat(result.folders().getFirst().attachmentCount()).isEqualTo(50L);
        assertThat(result.folders().getFirst().thumbnailUrl()).isEqualTo("place-url");
        assertThat(result.folders().getFirst().tripPlaceId()).isEqualTo(11L);
        assertThat(result.folders().getFirst().placeName()).isEqualTo("해변");
        verifyNoInteractions(regions);
    }

    @Test
    void 장소_없는_사진도_각각의_현재_여행_이름을_반환한다() {
        Trip firstTrip = Trip.localMock(1L, "수정된 제주 여행", LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1), "first-thumbnail");
        Trip secondTrip = Trip.localMock(1L, "부산 여행", LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1), "second-thumbnail");
        TripAttachment first = mock(TripAttachment.class);
        TripAttachment second = mock(TripAttachment.class);
        when(first.getTrip()).thenReturn(firstTrip);
        when(first.getTripId()).thenReturn(9L);
        when(first.getId()).thenReturn(7L);
        when(first.getPreviewStorageKey()).thenReturn("first-preview");
        when(second.getTrip()).thenReturn(secondTrip);
        when(second.getTripId()).thenReturn(10L);
        when(second.getId()).thenReturn(8L);
        when(second.getPreviewStorageKey()).thenReturn("second-preview");
        when(attachments.findSearchResults(1L, List.of(7L, 8L))).thenReturn(List.of(first, second));

        var result = service.findSearchResults(1L, List.of(7L, 8L));

        assertThat(result.attachments()).extracting(item -> item.tripName())
                .containsExactly("수정된 제주 여행", "부산 여행");
        assertThat(result.folders()).isEmpty();
        verifyNoInteractions(regions);
    }
}
