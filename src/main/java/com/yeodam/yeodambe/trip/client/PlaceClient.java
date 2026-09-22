package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class PlaceClient {
    private final RestClient restClient;
    private final String serviceKey;
    private final ObjectMapper objectMapper;

    public PlaceClient(
            @Value("${place.provider.base-url}") String baseUrl,
            @Value("${place.provider.service-key}") String serviceKey,
            @Value("${place.provider.timeout}") Duration timeout,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        this.serviceKey = serviceKey;
        this.objectMapper = objectMapper;
    }

    public ProviderPage search(String query, int pageNo) {
        try {

            String body = restClient.get()
                    .uri(builder -> builder
                            .queryParam("ServiceKey", "{serviceKey}")
                            .queryParam("pageNo", pageNo)
                            .queryParam("numOfRows", 10)
                            .queryParam("type", "json")
                            .queryParam("locatadd_nm", query)
                            .build(serviceKey)
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            return parse(body == null ? null : objectMapper.readTree(body));
        } catch (RestClientException | JacksonException e) {
            throw new PlaceQueryProviderUnavailableException(e);
        }
    }

    /**
     * 메서드에서 행안부 API의 응답구조를 검증.
     * 행안부 API 응답구조:
     * {
     *   "StanReginCd": [
     *     {
     *       "head": [
     *         { "totalCount": 11 },
     *         { "numOfRows": "10", "pageNo": "1", "type": "JSON" },
     *         {
     *           "RESULT": {
     *             "resultCode": "INFO-0",
     *             "resultMsg": "NOMAL SERVICE"
     *           }
     *         }
     *       ]
     *     },
     *     {
     *       "row": []
     *     }
     *   ]
     * }
     *
     */
    private ProviderPage parse(JsonNode body) {
        if (body == null) {
            throw new PlaceQueryProviderUnavailableException("응답 본문이 없습니다.");
        }

        JsonNode sections = body.path("StanReginCd");

        if (!sections.isArray() || sections.isEmpty()) {
            throw new PlaceQueryProviderUnavailableException("응답 구조가 올바르지 않습니다.");
        }

        JsonNode head = sections.get(0).path("head");

        String resultCode = findHeadValue(head, "RESULT")
                .path("resultCode")
                .asString();

        if (!"INFO-0".equals(resultCode)) {
            throw new PlaceQueryProviderUnavailableException("행안부 API 오류: " + resultCode);
        }

        int totalCount = findHeadValue(head, "totalCount").asInt(-1);

        if (totalCount < 0) {
            throw new PlaceQueryProviderUnavailableException("totalCount가 없습니다.");
        }

        List<ProviderRegion> rows = readRows(sections);

        return new ProviderPage(totalCount, rows);
    }

    private List<ProviderRegion> readRows(JsonNode sections) {
        if (sections.size() < 2) {
            return List.of();
        }

        JsonNode rowNode = sections.get(1).path("row");

        if (!rowNode.isArray()) {
            return List.of();
        }

        List<ProviderRegion> rows = new ArrayList<>();
        rowNode.forEach(row -> {
            rows.add(new ProviderRegion(
                    textOrNull(row, "sido_cd"),
                    textOrNull(row, "sgg_cd"),
                    textOrNull(row, "umd_cd"),
                    textOrNull(row, "ri_cd"),
                    textOrNull(row, "locatadd_nm")
            ));
        });

        return List.copyOf(rows);
    }

    private JsonNode findHeadValue(JsonNode head, String field) {
        for (JsonNode node : head) {
            if (node.has(field)) {
                return node.get(field);
            }
        }
        throw new PlaceQueryProviderUnavailableException("응답 필드가 없습니다: " + field);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asString();
    }


    public record ProviderPage(
            int totalCount,
            List<ProviderRegion> rows
    ) {

    }

    public record ProviderRegion(
            String sidoCd, // 시도 코드
            String sggCd, // 시군구 코드
            String umdCd, // 읍면동 코드
            String riCd, // 리 코드
            String regionName // 지역 주소 명
    ) {

    }
}
