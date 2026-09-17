package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import org.springframework.core.io.ClassPathResource;
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
        try (var input = new ClassPathResource("regions.json").getInputStream()) {
            root = objectMapper.readTree(input);
        }

        JsonNode entries = root.path("regions");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalStateException("regions.json의 regions가 비어 있습니다.");
        }

        HashMap<String, Region> loaded = new HashMap<>();
        for (JsonNode entry : entries) {
            String code = entry.path("regionCode").asString();
            String name = entry.path("regionName").asString();
            JsonNode latitude = entry.path("latitude");
            JsonNode longitude = entry.path("longitude");

            if (!code.matches("\\d{5}") || name.isBlank() || !latitude.isNumber() || !longitude.isNumber()) {
                throw new IllegalStateException("regions.json의 지역 정보가 잘못되었습니다: " + code);
            }
            Region region = new Region(code, name, latitude.decimalValue(), longitude.decimalValue());
            if (loaded.putIfAbsent(code, region) != null) {
                throw new IllegalStateException("regions.json의 지역 코드가 중복됩니다: " + code);
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

    public record Region(
            String code,
            String name,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }

}
