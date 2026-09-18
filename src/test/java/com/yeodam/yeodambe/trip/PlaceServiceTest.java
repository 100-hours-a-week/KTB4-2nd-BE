package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import com.yeodam.yeodambe.trip.client.PlaceClient;
import com.yeodam.yeodambe.trip.service.PlaceService;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlaceServiceTest {

    private final PlaceClient placeClient = mock(PlaceClient.class);
    private final PlaceService placeService;

    PlaceServiceTest() throws IOException {
        placeService = new PlaceService(placeClient, new RegionCatalog(new ObjectMapper()));
    }

    @Test
    void 모든_페이지에서_유효한_시군구_후보를_중복_없이_반환한다() {
        when(placeClient.search("제주", 1)).thenReturn(new PlaceClient.ProviderPage(11, List.of(
                region("50", "110", "000", "00", "제주특별자치도 제주시"),
                region("50", "110", "000", "00", "제주특별자치도 제주시"),
                region("50", "000", "000", "00", "제주특별자치도"),
                region("50", "110", "101", "00", "제주특별자치도 제주시 일도일동"),
                region("50", "110", "000", "01", "제주특별자치도 제주시 우도면 연평리"),
                region("5", "130", "000", "00", "잘못된 시도 코드"),
                region("50", "13", "000", "00", "잘못된 시군구 코드"),
                region("50", "130", "000", "00", null),
                region("50", "130", "101", "00", "제주특별자치도 서귀포시 성산읍"),
                region("50", "130", "000", null, "리 코드 누락")
        )));
        when(placeClient.search("제주", 2)).thenReturn(new PlaceClient.ProviderPage(11, List.of(
                region("50", "130", "000", "00", "제주특별자치도 서귀포시")
        )));

        assertThat(placeService.search(new PlaceSearchRequest("제주", null)).items()).containsExactly(
                new PlaceCandidateResponse("50110", "제주특별자치도 제주시"),
                new PlaceCandidateResponse("50130", "제주특별자치도 서귀포시")
        );
        verify(placeClient).search("제주", 1);
        verify(placeClient).search("제주", 2);
        verifyNoMoreInteractions(placeClient);
    }

    @Test
    void 요청한_페이지부터_조회한다() {
        when(placeClient.search("제주", 2)).thenReturn(new PlaceClient.ProviderPage(11, List.of(
                region("50", "130", "000", "00", "제주특별자치도 서귀포시")
        )));

        assertThat(placeService.search(new PlaceSearchRequest("제주", 2)).items()).containsExactly(
                new PlaceCandidateResponse("50130", "제주특별자치도 서귀포시")
        );
        verify(placeClient).search("제주", 2);
        verifyNoMoreInteractions(placeClient);
    }

    @Test
    void 검색_결과가_없으면_빈_목록을_반환한다() {
        when(placeClient.search("없는지역", 1)).thenReturn(new PlaceClient.ProviderPage(0, List.of()));

        assertThat(placeService.search(new PlaceSearchRequest("없는지역", null)).items()).isEmpty();
        verify(placeClient).search("없는지역", 1);
    }

    @Test
    void 제공자_장애를_전파한다() {
        when(placeClient.search("오류", 1)).thenThrow(new PlaceQueryProviderUnavailableException("장애"));

        assertThatThrownBy(() -> placeService.search(new PlaceSearchRequest("오류", null)))
                .isInstanceOf(PlaceQueryProviderUnavailableException.class);
    }

    @Test
    void 구는_상위_지역으로_묶고_시군은_유지한다() {
        when(placeClient.search("지역", 1)).thenReturn(new PlaceClient.ProviderPage(6, List.of(
                region("11", "110", "000", "00", "서울특별시 종로구"),
                region("11", "680", "000", "00", "서울특별시 강남구"),
                region("11", "000", "000", "00", "서울특별시"),
                region("41", "111", "000", "00", "경기도 수원시 장안구"),
                region("26", "710", "000", "00", "부산광역시 기장군"),
                region("41", "000", "000", "00", "경기도")
        )));

        assertThat(placeService.search(new PlaceSearchRequest("지역", null)).items()).containsExactly(
                new PlaceCandidateResponse("11000", "서울특별시"),
                new PlaceCandidateResponse("41110", "경기도 수원시"),
                new PlaceCandidateResponse("26710", "부산광역시 기장군")
        );
    }

    private static PlaceClient.ProviderRegion region(String sido, String sigungu, String eupmyeondong,
                                                      String ri, String name) {
        return new PlaceClient.ProviderRegion(sido, sigungu, eupmyeondong, ri, name);
    }
}
