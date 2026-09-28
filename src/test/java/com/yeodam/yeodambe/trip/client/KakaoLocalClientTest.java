package com.yeodam.yeodambe.trip.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoLocalClientTest {
    private KakaoLocalClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new KakaoLocalClient(
                "https://dapi.kakao.test", "test-key", Duration.ofSeconds(1));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
    }

    @Test
    void 경도와_위도를_WGS84로_보내고_건물명과_시군구를_읽는다() {
        server.expect(request -> {
            assertThat(request.getURI().getPath())
                    .isEqualTo("/v2/local/geo/coord2address.json");
            assertThat(request.getURI().getQuery())
                    .contains("x=126.94", "y=33.45", "input_coord=WGS84");
            assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("KakaoAK test-key");
        }).andRespond(withSuccess("""
                {"meta":{"total_count":1},"documents":[{
                  "road_address":{"address_name":"제주 서귀포시 성산읍 일출로 284-12",
                    "region_1depth_name":"제주특별자치도","region_2depth_name":"서귀포시",
                    "region_3depth_name":"성산읍","road_name":"일출로",
                    "underground_yn":"N","main_building_no":"284","sub_building_no":"12",
                    "building_name":"성산일출봉","zone_no":"63643"},
                  "address":{"address_name":"제주 서귀포시 성산읍 성산리 1",
                    "region_1depth_name":"제주특별자치도","region_2depth_name":"서귀포시",
                    "region_3depth_name":"성산읍 성산리","mountain_yn":"N",
                    "main_address_no":"1","sub_address_no":""}
                }]}
                """, MediaType.APPLICATION_JSON));

        KakaoLocalClient.LookupResult result = client.lookup(
                new BigDecimal("33.45"), new BigDecimal("126.94"));

        assertThat(result).isEqualTo(new KakaoLocalClient.LookupResult(
                "성산일출봉", "서귀포시", KakaoLocalClient.Failure.NONE));
        server.verify();
    }

    @Test
    void 시군구가_비어_있으면_시도를_지역명으로_사용한다() {
        server.expect(request -> {}).andRespond(withSuccess("""
                {"meta":{"total_count":1},"documents":[{
                  "road_address":null,
                  "address":{"address_name":"세종특별자치시 나성동",
                    "region_1depth_name":"세종특별자치시","region_2depth_name":"",
                    "region_3depth_name":"나성동","mountain_yn":"N",
                    "main_address_no":"1","sub_address_no":""}
                }]}
                """, MediaType.APPLICATION_JSON));

        KakaoLocalClient.LookupResult result = client.lookup(
                new BigDecimal("36.48"), new BigDecimal("127.29"));

        assertThat(result.regionName()).isEqualTo("세종특별자치시");
        assertThat(result.failure()).isEqualTo(KakaoLocalClient.Failure.NONE);
    }

    @Test
    void 비어_있거나_잘못된_응답은_OTHER로_분류한다() {
        server.expect(request -> {}).andRespond(withSuccess(
                "{\"meta\":{\"total_count\":0},\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")).failure())
                .isEqualTo(KakaoLocalClient.Failure.OTHER);

        server.reset();
        server.expect(request -> {}).andRespond(withSuccess("""
                {"documents":[{"road_address":{"building_name":123},"address":null}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")).failure())
                .isEqualTo(KakaoLocalClient.Failure.OTHER);
        server.verify();

        server.reset();
        server.expect(request -> {}).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")).failure())
                .isEqualTo(KakaoLocalClient.Failure.OTHER);
    }

    @Test
    void 재시도할_HTTP_오류를_RETRYABLE로_분류한다() {
        assertFailure(HttpStatus.TOO_MANY_REQUESTS, KakaoLocalClient.Failure.RETRYABLE);
        assertFailure(HttpStatus.INTERNAL_SERVER_ERROR, KakaoLocalClient.Failure.RETRYABLE);
    }

    @Test
    void 인증_오류와_그밖의_요청_오류를_구분한다() {
        assertFailure(HttpStatus.UNAUTHORIZED, KakaoLocalClient.Failure.AUTH);
        assertFailure(HttpStatus.FORBIDDEN, KakaoLocalClient.Failure.AUTH);
        assertFailure(HttpStatus.BAD_REQUEST, KakaoLocalClient.Failure.OTHER);
    }

    @Test
    void 연결과_읽기_실패는_RETRYABLE로_분류한다() {
        server.expect(request -> {}).andRespond(request -> {
            throw new ResourceAccessException("timeout");
        });

        assertThat(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")).failure())
                .isEqualTo(KakaoLocalClient.Failure.RETRYABLE);
        server.verify();
    }

    private void assertFailure(HttpStatus status, KakaoLocalClient.Failure expected) {
        server.reset();
        server.expect(request -> {}).andRespond(withStatus(status));

        assertThat(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")).failure())
                .isEqualTo(expected);
        server.verify();
    }
}
