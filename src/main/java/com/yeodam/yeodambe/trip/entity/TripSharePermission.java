package com.yeodam.yeodambe.trip.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.yeodam.yeodambe.common.exception.InvalidTripShareRequestException;
import tools.jackson.databind.JsonNode;

public enum TripSharePermission {
    READ,
    WRITE;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TripSharePermission fromJson(JsonNode value) {
        if (value != null && value.isString()) {
            return switch (value.asString()) {
                case "READ" -> READ;
                case "WRITE" -> WRITE;
                default -> throw new InvalidTripShareRequestException();
            };
        }
        throw new InvalidTripShareRequestException();
    }
}
