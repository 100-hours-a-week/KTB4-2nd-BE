package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class RegionCatalog {
    private final Map<String, Region> regions;

    public RegionCatalog(ObjectMapper objectMapper) throws IOException {
        JsonNode root;
        try (var input = new FileSystemResource("data/regions.json").getInputStream()) {
            root = objectMapper.readTree(input);
        }

        JsonNode entries = root.path("regions");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalStateException(TripInternalErrorMessage.REGION_CATALOG_EMPTY.message());
        }

        HashMap<String, Region> loaded = new HashMap<>();
        for (JsonNode entry : entries) {
            String code = entry.path("regionCode").asString();
            String name = entry.path("regionName").asString();
            JsonNode latitude = entry.path("latitude");
            JsonNode longitude = entry.path("longitude");

            if (!code.matches("\\d{5}") || name.isBlank() || !latitude.isNumber() || !longitude.isNumber()) {
                throw new IllegalStateException(TripInternalErrorMessage.REGION_CATALOG_ENTRY_INVALID.message().formatted(code));
            }
            Region region = new Region(code, name, latitude.decimalValue(), longitude.decimalValue());
            if (loaded.putIfAbsent(code, region) != null) {
                throw new IllegalStateException(TripInternalErrorMessage.REGION_CATALOG_DUPLICATE_CODE.message().formatted(code));
            }
        }
        this.regions = Collections.unmodifiableMap(loaded);
    }

    public Region getRequired(String code) {
        Region region = regions.get(code);
        if (region == null) {
            throw new InvalidTripRequestException();
        }
        return region;
    }

    public Region findByName(String name) {
        return regions.values().stream()
                .filter(region -> region.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public record Region(
            String code,
            String name,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }

}
