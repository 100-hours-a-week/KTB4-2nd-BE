package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.PlaceFolderAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.request.PlaceFolderCursor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripPlaceFolderListServiceTest {
    private final TripAccessService accessService = mock(TripAccessService.class);
    private final TripDetailPlaceRepository placeRepository = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachmentRepository = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storageClient = mock(TripAttachmentStorageClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TripPlaceFolderListService service = new TripPlaceFolderListService(
            accessService, placeRepository, attachmentRepository, storageClient, objectMapper);

    @Test
    void 일곱개를_조회하면_여섯개와_여섯번째_폴더_커서를_반환한다() {
        List<TripDetailPlace> fetched = new ArrayList<>();
        for (long id = 1; id <= 7; id++) {
            fetched.add(place(id, "장소 " + id, id == 1 ? "thumb/1.webp" : null));
        }
        when(placeRepository.findPlaceFoldersWithCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class))).thenReturn(fetched);
        when(attachmentRepository.countActiveByTripPlaceIds(List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .thenReturn(List.of(new PlaceFolderAttachmentCount(1L, 3L)));
        when(storageClient.createReadUrl("thumb/1.webp")).thenReturn("https://cdn.test/1");

        var response = service.findPlaceFolders(1L, 7L, null);

        assertThat(response.items()).hasSize(6);
        assertThat(response.items().getFirst().attachmentCount()).isEqualTo(3L);
        assertThat(response.items().getFirst().thumbnailUrl()).isEqualTo("https://cdn.test/1");
        assertThat(response.items().get(1).attachmentCount()).isZero();
        assertThat(response.hasNext()).isTrue();
        assertThat(PlaceFolderCursor.decode(response.nextCursor(), 7L, objectMapper))
                .isEqualTo(new PlaceFolderCursor(7L, "장소 6", 6L));
        verify(attachmentRepository, times(1))
                .countActiveByTripPlaceIds(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        verifyNoMoreInteractions(attachmentRepository);
    }

    @Test
    void 다음_커서와_일곱개_조회조건을_저장소에_전달한다() {
        String cursor = new PlaceFolderCursor(7L, "제주", 11L).encode(objectMapper);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(placeRepository.findPlaceFoldersWithCursor(
                eq(7L), eq("제주"), eq(11L), any(Pageable.class))).thenReturn(List.of());

        service.findPlaceFolders(1L, 7L, cursor);

        verify(placeRepository).findPlaceFoldersWithCursor(
                eq(7L), eq("제주"), eq(11L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void 결과가_없으면_첨부수와_스토리지를_조회하지_않는다() {
        when(placeRepository.findPlaceFoldersWithCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class))).thenReturn(List.of());

        var response = service.findPlaceFolders(1L, 7L, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verifyNoInteractions(attachmentRepository, storageClient);
    }

    @Test
    void 여섯개를_조회하면_다음_페이지가_없다() {
        List<TripDetailPlace> fetched = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            fetched.add(place(id, "장소 " + id, null));
        }
        when(placeRepository.findPlaceFoldersWithCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class))).thenReturn(fetched);

        var response = service.findPlaceFolders(1L, 7L, null);

        assertThat(response.items()).hasSize(6);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 썸네일키가_빈값이면_스토리지를_호출하지_않는다() {
        List<TripDetailPlace> fetched = List.of(
                place(1L, "장소 1", ""),
                place(2L, "장소 2", "   ")
        );
        when(placeRepository.findPlaceFoldersWithCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class))).thenReturn(fetched);

        var response = service.findPlaceFolders(1L, 7L, null);

        assertThat(response.items()).extracting(item -> item.thumbnailUrl())
                .containsExactly(null, null);
        verifyNoInteractions(storageClient);
    }

    @Test
    void 접근할수없는_여행은_커서를_검증하기_전에_거부한다() {
        doThrow(new TripNotFoundException()).when(accessService).requireReadableTrip(7L, 1L);

        assertThatThrownBy(() -> service.findPlaceFolders(1L, 7L, "bad-cursor"))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(placeRepository, attachmentRepository, storageClient);
    }

    private TripDetailPlace place(Long id, String name, String thumbnailKey) {
        TripDetailPlace place = mock(TripDetailPlace.class);
        when(place.getId()).thenReturn(id);
        when(place.getTripId()).thenReturn(7L);
        when(place.getPlaceName()).thenReturn(name);
        when(place.getThumbnailKey()).thenReturn(thumbnailKey);
        return place;
    }
}
