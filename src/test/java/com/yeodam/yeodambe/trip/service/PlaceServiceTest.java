package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlaceServiceTest {
    private final PlaceSearchCatalog catalog = mock(PlaceSearchCatalog.class);
    private final RegionCatalog regions = new RegionCatalog(new ObjectMapper());
    private final PlaceService service = new PlaceService(catalog, regions);

    PlaceServiceTest() throws IOException {
    }

    @Test
    void 구_검색은_상위_지역을_반환하고_군은_유지한다() {
        when(catalog.entries()).thenReturn(List.of(
                new PlaceSearchCatalog.Entry("서울특별시 강남구", "11000"),
                new PlaceSearchCatalog.Entry("경기도 수원시 영통구", "41110"),
                new PlaceSearchCatalog.Entry("부산광역시 기장군", "26710")));

        assertThat(service.search(new PlaceSearchRequest("강남", null)).items())
                .containsExactly(new PlaceCandidateResponse("11000", "서울특별시"));
        assertThat(service.search(new PlaceSearchRequest("영통", null)).items())
                .containsExactly(new PlaceCandidateResponse("41110", "경기도 수원시"));
        assertThat(service.search(new PlaceSearchRequest("기장", null)).items())
                .containsExactly(new PlaceCandidateResponse("26710", "부산광역시 기장군"));
        verify(catalog, times(3)).entries();
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void 중복_제거와_가나다순_정렬_후_요청한_페이지만_반환한다() {
        var expected = regions.all().stream()
                .filter(region -> region.name().contains("경기도"))
                .sorted(Comparator.comparing(RegionCatalog.Region::name)
                        .thenComparing(RegionCatalog.Region::code))
                .map(region -> new PlaceCandidateResponse(region.code(), region.name())).toList();
        var entries = new ArrayList<>(regions.all().stream()
                .map(region -> new PlaceSearchCatalog.Entry(region.name(), region.code())).toList());
        entries.add(new PlaceSearchCatalog.Entry("경기도 수원시 영통구", "41110"));
        when(catalog.entries()).thenReturn(entries);

        assertThat(expected.size()).isGreaterThan(20);
        assertThat(service.search(new PlaceSearchRequest("경기", null)).items())
                .containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(service.search(new PlaceSearchRequest("경기", 2)).items())
                .containsExactlyElementsOf(expected.subList(10, 20));
        assertThat(service.search(new PlaceSearchRequest("경기", Integer.MAX_VALUE)).items()).isEmpty();
        assertThat(service.search(new PlaceSearchRequest("없는지역", 1)).items()).isEmpty();
        verify(catalog, times(4)).entries();
        verifyNoMoreInteractions(catalog);
    }
}
