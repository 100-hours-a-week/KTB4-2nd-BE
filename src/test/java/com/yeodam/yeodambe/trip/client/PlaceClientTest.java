package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlaceClientTest {
    private PlaceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new PlaceClient("https://provider.example/regions", "test-key", Duration.ofSeconds(1));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://provider.example/regions");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
    }

    @Test
    void 검색_인자와_행안부_응답을_지역_행으로_변환한다() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/regions");
            assertThat(request.getURI().getQuery()).contains(
                    "ServiceKey=test-key", "pageNo=2", "numOfRows=10",
                    "type=json", "locatadd_nm=제주");
            assertThat(request.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
        }).andRespond(withSuccess(response("INFO-0", 11,
                """
                ,{"row":[{"sido_cd":"50","sgg_cd":"110","umd_cd":"000",
                "ri_cd":"00","locatadd_nm":"제주특별자치도 제주시"}]}
                """), MediaType.APPLICATION_JSON));

        var result = client.search("제주", 2);

        assertThat(result.totalCount()).isEqualTo(11);
        assertThat(result.rows()).containsExactly(new PlaceClient.ProviderRegion(
                "50", "110", "000", "00", "제주특별자치도 제주시"));
        server.verify();
    }

    @Test
    void 결과가_없으면_빈_행을_반환한다() {
        server.expect(request -> {}).andRespond(withSuccess(
                response("INFO-0", 0, ""), MediaType.APPLICATION_JSON));

        assertThat(client.search("없음", 1).rows()).isEmpty();
        server.verify();
    }

    @Test
    void 제공자_오류_코드와_잘못된_응답을_거부한다() {
        server.expect(request -> {}).andRespond(withSuccess(
                response("ERROR-10", 0, ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.search("서울", 1))
                .isInstanceOf(PlaceQueryProviderUnavailableException.class);
        server.verify();
    }

    @Test
    void 잘못된_응답_구조를_거부한다() {
        server.expect(request -> {}).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.search("서울", 1))
                .isInstanceOf(PlaceQueryProviderUnavailableException.class);
        server.verify();
    }

    @Test
    void HTTP_오류를_제공자_오류로_변환한다() {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.search("서울", 1))
                .isInstanceOf(PlaceQueryProviderUnavailableException.class);
        server.verify();
    }

    private static String response(String code, int totalCount, String rows) {
        return """
                {"StanReginCd":[{"head":[{"totalCount":%d},
                {"numOfRows":"10","pageNo":"1","type":"JSON"},
                {"RESULT":{"resultCode":"%s","resultMsg":"NOMAL SERVICE"}}]}%s]}
                """.formatted(totalCount, code, rows);
    }
}
