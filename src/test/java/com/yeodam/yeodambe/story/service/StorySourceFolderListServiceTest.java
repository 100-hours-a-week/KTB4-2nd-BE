package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.response.TripPlaceFolderListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorySourceFolderListServiceTest {
    private final TripPlaceFolderListService tripPlaceFolderListService =
            mock(TripPlaceFolderListService.class);
    private final StorySourceFolderListService service =
            new StorySourceFolderListService(tripPlaceFolderListService);

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
        when(tripPlaceFolderListService.findPlaceFolders(1L, 7L, null))
                .thenThrow(new TripNotFoundException());

        assertThatThrownBy(() -> service.findFolders(1L, 7L, null))
                .isInstanceOf(TripNotFoundException.class);
    }
}
