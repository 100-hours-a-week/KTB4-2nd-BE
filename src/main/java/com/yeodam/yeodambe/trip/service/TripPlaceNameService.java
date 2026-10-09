package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.trip.client.KakaoLocalClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
public class TripPlaceNameService {
    private static final int MAX_NAME_LENGTH = 50;

    private final KakaoLocalClient client;
    private final InitialUploadExecutionRegistry executions;
    private final int maxConcurrency;

    public TripPlaceNameService(
            KakaoLocalClient client,
            InitialUploadExecutionRegistry executions,
            @Value("${kakao.local.max-concurrency}") int maxConcurrency
    ) {
        if (maxConcurrency < 1 || maxConcurrency > 5) {
            throw new IllegalArgumentException(TripInternalErrorMessage.KAKAO_PLACE_CONCURRENCY_INVALID.message());
        }
        this.client = client;
        this.executions = executions;
        this.maxConcurrency = maxConcurrency;
    }

    public Map<String, String> resolve(Long tripId, String executionId, JsonNode result) {
        return resolve(tripId, executionId, result, () -> executions.isCurrent(tripId, executionId));
    }

    public Map<String, String> resolve(
            Long tripId, String executionId, JsonNode result, BooleanSupplier currentExecution
    ) {
        List<Place> places = validate(result);

        LinkedHashMap<Coordinate, Place> unique = new LinkedHashMap<>();
        for (Place place : places) unique.putIfAbsent(place.coordinate(), place);

        Map<Coordinate, KakaoLocalClient.LookupResult> lookups = lookupAll(
                tripId, executionId, new ArrayList<>(unique.entrySet()), currentExecution);
        List<String> bases = new ArrayList<>(places.size());

        for (Place place : places) {
            KakaoLocalClient.LookupResult lookup = lookups.get(place.coordinate());
            String name = lookup == null ? null : firstText(lookup.buildingName(), lookup.regionName());
            if (name == null) name = "장소 " + place.order();
            bases.add(name);
        }

        Map<String, Integer> counts = new HashMap<>();

        for (String name : bases) counts.merge(name, 1, Integer::sum);

        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();

        for (int i = 0; i < places.size(); i++) {
            String base = bases.get(i);
            String suffix = counts.get(base) > 1 ? " " + seen.merge(base, 1, Integer::sum) : "";
            names.put(places.get(i).id(), truncate(base, MAX_NAME_LENGTH - codePointCount(suffix)) + suffix);
        }

        return Collections.unmodifiableMap(names);
    }

    private Map<Coordinate, KakaoLocalClient.LookupResult> lookupAll(
            Long tripId,
            String executionId,
            List<Map.Entry<Coordinate, Place>> coordinates,
            BooleanSupplier currentExecution
    ) {
        Map<Coordinate, KakaoLocalClient.LookupResult> results = new HashMap<>();
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency);
        try {
            for (int from = 0; from < coordinates.size(); from += maxConcurrency) {
                if (!currentExecution.getAsBoolean()) {
                    throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
                }

                List<Map.Entry<Coordinate, Place>> chunk = coordinates.subList(
                        from, Math.min(from + maxConcurrency, coordinates.size()));
                List<Future<KakaoLocalClient.LookupResult>> futures = chunk.stream()
                        .map(entry -> executor.submit(() -> {
                            if (callerMdc == null) MDC.clear();
                            else MDC.setContextMap(callerMdc);
                            try {
                                return lookup(tripId, executionId, entry.getValue());
                            } finally {
                                MDC.clear();
                            }
                        }))
                        .toList();

                for (int i = 0; i < chunk.size(); i++) {
                    results.put(chunk.get(i).getKey(), await(futures.get(i)));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private KakaoLocalClient.LookupResult lookup(Long tripId, String executionId, Place place) {
        int attempts = 1;

        KakaoLocalClient.LookupResult result = client.lookup(place.latitude(), place.longitude());

        if (result.failure() == KakaoLocalClient.Failure.RETRYABLE) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(TripInternalErrorMessage.KAKAO_PLACE_LOOKUP_INTERRUPTED.message(), e);
            }
            attempts++;
            result = client.lookup(place.latitude(), place.longitude());
        }

        if (result.failure() != KakaoLocalClient.Failure.NONE) {
            if (result.failure() == KakaoLocalClient.Failure.AUTH) {
                log.error("Kakao 장소명 인증 실패: tripId={}, executionId={}, placeId={}, attempts={}",
                        tripId, executionId, place.id(), attempts);

            } else {
                log.warn("Kakao 장소명 조회 실패: tripId={}, executionId={}, placeId={}, attempts={}, failure={}",
                        tripId, executionId, place.id(), attempts, result.failure());
            }
        }
        return result;
    }

    private KakaoLocalClient.LookupResult await(Future<KakaoLocalClient.LookupResult> future) {
        try {
            return future.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.KAKAO_PLACE_LOOKUP_INTERRUPTED.message(), e);

        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;

            throw new IllegalStateException(TripInternalErrorMessage.KAKAO_PLACE_LOOKUP_FAILED.message(), e.getCause());
        }
    }

    private List<Place> validate(JsonNode result) {
        JsonNode nodes = result == null ? null : result.path("places");
        if (nodes == null || !nodes.isArray()) throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_RESULT_INVALID.message());

        List<Place> places = new ArrayList<>();
        Map<String, Boolean> ids = new HashMap<>();

        int order = 0;

        for (JsonNode node : nodes) {
            String id = node.path("place_id").asString().trim();

            if (id.isEmpty() || ids.put(id, true) != null) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_RESULT_INVALID.message());
            }

            BigDecimal latitude = coordinate(node, "latitude", -90, 90);
            BigDecimal longitude = coordinate(node, "longitude", -180, 180);

            places.add(new Place(id, ++order, latitude, longitude,
                    new Coordinate(normalize(latitude), normalize(longitude))));
        }
        return places;
    }

    private BigDecimal coordinate(JsonNode node, String field, int minimum, int maximum) {
        JsonNode value = node.path(field);

        if (!value.isNumber()) throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_COORDINATE_INVALID.message());

        BigDecimal coordinate = value.decimalValue();

        if (coordinate.compareTo(BigDecimal.valueOf(minimum)) < 0
                || coordinate.compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_COORDINATE_INVALID.message());
        }
        return coordinate;
    }

    private String normalize(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private String truncate(String value, int maximum) {
        if (codePointCount(value) <= maximum) return value;
        int end = value.offsetByCodePoints(0, maximum);
        return value.substring(0, end);
    }

    private int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private record Place(
            String id,
            int order,
            BigDecimal latitude,
            BigDecimal longitude,
            Coordinate coordinate
    ) {
    }

    private record Coordinate(String latitude, String longitude) {
    }
}
