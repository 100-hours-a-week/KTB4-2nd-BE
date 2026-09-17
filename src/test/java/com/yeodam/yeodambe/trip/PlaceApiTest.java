package com.yeodam.yeodambe.trip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(PlaceApiTest.UnexpectedFailureController.class)
class PlaceApiTest {

    private static final String PROVIDER_PATH = "/1741000/StanReginCd/getStanReginCdList";
    private static final List<Map<String, String>> providerRequests = new ArrayList<>();
    private static HttpServer provider;
    private static ProviderScenario scenario = ProviderScenario.SUCCESS;

    private final MockMvc mockMvc;

    @Autowired
    PlaceApiTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @BeforeAll
    static void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress(0), 0);
        provider.createContext(PROVIDER_PATH, PlaceApiTest::respond);
        provider.start();
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @AfterEach
    void resetProvider() {
        scenario = ProviderScenario.SUCCESS;
        providerRequests.clear();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("place.provider.base-url",
                () -> "http://localhost:" + provider.getAddress().getPort() + PROVIDER_PATH);
        registry.add("place.provider.service-key", () -> "test-service-key");
        registry.add("place.provider.timeout", () -> "100ms");
    }

    @Test
    void 미인증_사용자의_검색을_거부한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "제 주", "제주1", "Jeju"})
    void 잘못된_검색어를_거부한다(String query) throws Exception {
        assertInvalidQuery(get("/places").param("query", query));
    }

    @Test
    void 검색어가_누락되면_거부한다() throws Exception {
        assertInvalidQuery(get("/places"));
    }

    @Test
    void 페이지_번호가_1보다_작으면_거부한다() throws Exception {
        assertInvalidQuery(get("/places").param("query", "제주").param("pageNo", "0"));
    }

    @Test
    @WithMockUser
    void 외부_API_모든_페이지에서_유효한_시군구_후보를_중복_없이_반환한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PLACE_CANDIDATES_FOUND"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("50110"))
                .andExpect(jsonPath("$.data.items[0].regionName").value("제주특별자치도 제주시"))
                .andExpect(jsonPath("$.data.items[1].regionCode").value("50130"))
                .andExpect(jsonPath("$.data.items[1].regionName").value("제주특별자치도 서귀포시"))
                .andExpect(jsonPath("$.data.items[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].longitude").doesNotExist());

        assertThat(providerRequests).containsExactly(
                expectedProviderRequest("제주", "1"),
                expectedProviderRequest("제주", "2")
        );
    }

    @Test
    @WithMockUser
    void 요청한_페이지부터_외부_API를_조회한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주").param("pageNo", "2"))
                .andExpect(status().isOk());

        assertThat(providerRequests).containsExactly(expectedProviderRequest("제주", "2"));
    }

    @Test
    @WithMockUser
    void 검색_결과가_없으면_빈_목록을_반환한다() throws Exception {
        scenario = ProviderScenario.EMPTY;

        mockMvc.perform(get("/places").param("query", "없는지역"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PLACE_CANDIDATES_FOUND"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_ERROR", "RESULT_ERROR", "DISCONNECTED", "TIMEOUT"})
    void 외부_API_장애를_서비스_사용_불가로_변환한다(String scenarioName) throws Exception {
        scenario = ProviderScenario.valueOf(scenarioName);

        mockMvc.perform(get("/places").with(user("member")).param("query", "오류"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("MAP_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser
    void 예상하지_못한_오류를_내부_서버_오류로_변환한다() {
        assertThatCode(() -> mockMvc.perform(get("/test/places/unexpected-error"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.message").value("INTERNAL_SERVER_ERROR"))
                        .andExpect(jsonPath("$.data").doesNotExist()))
                .doesNotThrowAnyException();
    }

    private void assertInvalidQuery(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.with(user("member")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static Map<String, String> expectedProviderRequest(String query, String pageNo) {
        return Map.of(
                "ServiceKey", "test-service-key",
                "pageNo", pageNo,
                "numOfRows", "10",
                "type", "json",
                "locatadd_nm", query
        );
    }

    private static void respond(HttpExchange exchange) throws IOException {
        providerRequests.add(parseQuery(exchange.getRequestURI().getRawQuery()));

        switch (scenario) {
            case HTTP_ERROR -> send(exchange, 500, "{}");
            case RESULT_ERROR -> send(exchange, 200, providerResponse("ERROR-10", 0, "1", ""));
            case DISCONNECTED -> exchange.close();
            case TIMEOUT -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                send(exchange, 200, providerResponse("INFO-0", 0, "1", ""));
            }
            case EMPTY -> send(exchange, 200, providerResponse("INFO-0", 0, "1", ""));
            case SUCCESS -> {
                String pageNo = parseQuery(exchange.getRequestURI().getRawQuery()).get("pageNo");
                send(exchange, 200, "2".equals(pageNo) ? secondPage() : firstPage());
            }
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String firstPage() {
        return providerResponse("INFO-0", 11, "1", """
                {"sido_cd":"50","sgg_cd":"110","umd_cd":"000","ri_cd":"00","locatadd_nm":"제주특별자치도 제주시"},
                {"sido_cd":"50","sgg_cd":"110","umd_cd":"000","ri_cd":"00","locatadd_nm":"제주특별자치도 제주시"},
                {"sido_cd":"50","sgg_cd":"000","umd_cd":"000","ri_cd":"00","locatadd_nm":"제주특별자치도"},
                {"sido_cd":"50","sgg_cd":"110","umd_cd":"101","ri_cd":"00","locatadd_nm":"제주특별자치도 제주시 일도일동"},
                {"sido_cd":"50","sgg_cd":"110","umd_cd":"000","ri_cd":"01","locatadd_nm":"제주특별자치도 제주시 우도면 연평리"},
                {"sido_cd":"5","sgg_cd":"130","umd_cd":"000","ri_cd":"00","locatadd_nm":"잘못된 시도 코드"},
                {"sido_cd":"50","sgg_cd":"13","umd_cd":"000","ri_cd":"00","locatadd_nm":"잘못된 시군구 코드"},
                {"sido_cd":"50","sgg_cd":"130","umd_cd":"000","ri_cd":"00"},
                {"sido_cd":"50","sgg_cd":"130","umd_cd":"101","ri_cd":"00","locatadd_nm":"제주특별자치도 서귀포시 성산읍"},
                {"sido_cd":"50","sgg_cd":"130","umd_cd":"000","locatadd_nm":"리 코드 누락"}
                """);
    }

    private static String secondPage() {
        return providerResponse("INFO-0", 11, "2", """
                {"sido_cd":"50","sgg_cd":"130","umd_cd":"000","ri_cd":"00","locatadd_nm":"제주특별자치도 서귀포시"}
                """);
    }

    private static String providerResponse(String resultCode, int totalCount, String pageNo, String rows) {
        String rowSection = rows.isBlank() ? "" : ", {\"row\":[" + rows + "]}";
        return """
                {"StanReginCd":[
                  {"head":[
                    {"totalCount":%d},
                    {"numOfRows":"10","pageNo":"%s","type":"JSON"},
                    {"RESULT":{"resultCode":"%s","resultMsg":"NOMAL SERVICE"}}
                  ]}%s
                ]}
                """.formatted(totalCount, pageNo, resultCode, rowSection);
    }

    enum ProviderScenario {
        SUCCESS,
        EMPTY,
        HTTP_ERROR,
        RESULT_ERROR,
        DISCONNECTED,
        TIMEOUT
    }

    @RestController
    static class UnexpectedFailureController {

        @GetMapping("/test/places/unexpected-error")
        void fail() {
            throw new IllegalStateException("unexpected");
        }
    }
}
