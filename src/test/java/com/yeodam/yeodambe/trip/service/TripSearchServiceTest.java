package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.TripSearchCondition;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripSearchServiceTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripService service = new TripService(mock(TripRepository.class), regions,
            mock(RegionCatalog.class), attachments, storage, mock(TripAccessService.class),
            new tools.jackson.databind.ObjectMapper(), mock(StoryRepository.class));

    @Test
    void 사진_ID가_없으면_DB_조회와_URL_생성을_생략한다() {
        assertThat(service.findSearchResults(1L, List.of()).attachments()).isEmpty();
        assertThat(service.findSearchResults(1L, List.of()).folders()).isEmpty();
        verifyNoInteractions(attachments, regions, storage);
    }

    @Test
    void 종료일_다음날을_제외하고_지역_목록이_비어있으면_지역_필터를_생략한다() {
        when(attachments.findSearchCandidateIds(1L, LocalDate.of(2024, 2, 29).atStartOfDay(),
                LocalDate.of(2024, 3, 1).atStartOfDay(), false, List.of(""))).thenReturn(List.of(7L));
        assertThat(service.findSearchCandidateIds(1L, new TripSearchCondition(
                LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29), List.of())))
                .containsExactly(7L);
    }

    @Test
    void 결과에_저장된_썸네일과_여행_전체의_활성_사진_개수를_사용한다() {
        Trip trip = Trip.localMock(1L, "제주 여행", LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1), "trip-thumbnail");
        org.springframework.test.util.ReflectionTestUtils.setField(trip, "id", 9L);
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getId()).thenReturn(7L);
        when(photo.getTripId()).thenReturn(9L);
        when(photo.getTrip()).thenReturn(trip);
        when(photo.getTripPlaceId()).thenReturn(11L);
        when(photo.getPreviewStorageKey()).thenReturn("photo-preview");
        when(attachments.findSearchResults(1L, List.of(7L))).thenReturn(List.of(photo));
        when(attachments.countActiveByTripIds(List.of(9L)))
                .thenReturn(List.of(new TripAttachmentCount(9L, 50L)));
        when(regions.findNamesByTripIds(List.of(9L))).thenReturn(List.of(
                new TripRegionName(9L, "제주"), new TripRegionName(9L, "부산")));
        when(storage.createReadUrl("trip-thumbnail")).thenReturn("trip-url");
        when(storage.createReadUrl("photo-preview")).thenReturn("photo-url");
        var result = service.findSearchResults(1L, List.of(7L));
        assertThat(result.attachments().getFirst().thumbnailUrl()).isEqualTo("photo-url");
        assertThat(result.folders().getFirst().attachmentCount()).isEqualTo(50L);
        assertThat(result.folders().getFirst().thumbnailUrl()).isEqualTo("trip-url");
        assertThat(result.folders().getFirst().regionNames()).containsExactly("제주", "부산");
    }
}
