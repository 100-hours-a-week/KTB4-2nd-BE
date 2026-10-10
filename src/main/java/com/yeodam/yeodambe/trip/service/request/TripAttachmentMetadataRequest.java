package com.yeodam.yeodambe.trip.service.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

@JsonDeserialize(using = TripAttachmentMetadataRequest.MetadataDeserializer.class)
public record TripAttachmentMetadataRequest(
        OffsetDateTime takenAt,
        @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude
) {
    public static TripAttachmentMetadataRequest fromJson(JsonNode node) {
        boolean hasRequiredFields = node.isObject() && node.has("takenAt")
                && node.has("latitude") && node.has("longitude");
        if (!hasRequiredFields) {
            throw new IllegalArgumentException("Photo metadata requires all three fields");
        }
        return new TripAttachmentMetadataRequest(
                parseTakenAt(node.get("takenAt")),
                parseCoordinate(node.get("latitude")),
                parseCoordinate(node.get("longitude"))
        );
    }

    private static OffsetDateTime parseTakenAt(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("Photo timestamp must be a string or null");
        }
        try {
            return OffsetDateTime.parse(node.asString());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Photo timestamp requires an ISO 8601 offset");
        }
    }

    private static BigDecimal parseCoordinate(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Photo coordinate must be a number or null");
        }
        return node.asDecimal();
    }

    @AssertTrue
    @JsonIgnore
    public boolean isGpsPairValid() {
        return (latitude == null) == (longitude == null);
    }

    static final class MetadataDeserializer extends ValueDeserializer<TripAttachmentMetadataRequest> {
        // 좌표 범위 검사 전에 double 반올림으로 원본 소수 정밀도를 잃지 않는다.
        private static final ObjectReader reader = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build()
                .readerFor(JsonNode.class)
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        @Override
        public TripAttachmentMetadataRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = reader.readValue(parser);
            return fromJson(node);
        }
    }
}
