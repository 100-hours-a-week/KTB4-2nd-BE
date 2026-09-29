package com.yeodam.yeodambe.trip.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class KakaoLocalClient {
    private final RestClient restClient;
    private final String apiKey;

    public KakaoLocalClient(
            @Value("${kakao.local.base-url}") String baseUrl,
            @Value("${oauth.kakao.client-id}") String apiKey,
            @Value("${kakao.local.timeout}") Duration timeout
    ) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.apiKey = apiKey;
    }

    public LookupResult lookup(BigDecimal latitude, BigDecimal longitude) {
        try {
            JsonNode body = restClient.get()
                    .uri(builder -> builder
                            .path("/v2/local/geo/coord2address.json")
                            .queryParam("x", longitude.toPlainString())
                            .queryParam("y", latitude.toPlainString())
                            .queryParam("input_coord", "WGS84")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(body);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status >= 500) return LookupResult.failed(Failure.RETRYABLE);
            if (status == 401 || status == 403) return LookupResult.failed(Failure.AUTH);
            return LookupResult.failed(Failure.OTHER);
        } catch (ResourceAccessException e) {
            return LookupResult.failed(Failure.RETRYABLE);
        } catch (RestClientException e) {
            return LookupResult.failed(Failure.OTHER);
        }
    }

    private LookupResult parse(JsonNode body) {
        JsonNode documents = body == null ? null : body.path("documents");
        if (documents == null || !documents.isArray() || documents.isEmpty()) {
            return LookupResult.failed(Failure.OTHER);
        }

        JsonNode document = documents.get(0);
        String buildingName = text(document.path("road_address"), "building_name");
        JsonNode address = document.path("address");
        String regionName = text(address, "region_2depth_name");

        if (regionName == null) regionName = text(address, "region_1depth_name");
        if (buildingName == null && regionName == null) return LookupResult.failed(Failure.OTHER);

        return new LookupResult(buildingName, regionName, Failure.NONE);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode valueNode = node.get(field);
        if (valueNode == null || !valueNode.isString()) return null;
        String value = valueNode.asString().trim();
        return value.isEmpty() ? null : value;
    }

    public record LookupResult(String buildingName, String regionName, Failure failure) {
        private static LookupResult failed(Failure failure) {
            return new LookupResult(null, null, failure);
        }
    }

    public enum Failure {
        NONE,
        RETRYABLE,
        AUTH,
        OTHER
    }
}
