package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.PlaceClient;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlaceSearchCatalogSeedTest {
    @Test
    void 배포용_초기_파일로_외부_호출_없이_검색을_시작한다() throws Exception {
        var client = mock(PlaceClient.class);
        var mapper = new ObjectMapper();
        var regions = new RegionCatalog(mapper);
        var catalog = new PlaceSearchCatalog("data/place-search-catalog.json", mapper, regions, client);
        var service = new PlaceService(catalog, regions);

        assertThat(catalog.isReady()).isTrue();
        new PlaceSearchCatalogScheduler(catalog).initialize();
        assertThat(service.search(new PlaceSearchRequest("강남", 1)).items())
                .containsExactly(new PlaceCandidateResponse("11000", "서울특별시"));
        assertThat(service.search(new PlaceSearchRequest("영통", 1)).items())
                .containsExactly(new PlaceCandidateResponse("41110", "경기도 수원시"));
        assertThat(catalog.entries().stream().map(PlaceSearchCatalog.Entry::regionCode).distinct().count())
                .isEqualTo(regions.all().size());
        verifyNoInteractions(client);
    }
}
