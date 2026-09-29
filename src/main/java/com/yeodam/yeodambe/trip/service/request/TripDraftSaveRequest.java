package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripDraftRequestException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record TripDraftSaveRequest(String tripName, List<String> regionCodes, LocalDate startDate, LocalDate endDate) {
    public static TripDraftSaveRequest fromJson(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 4 || !root.has("tripName")
                || !root.has("regionCodes") || !root.has("startDate") || !root.has("endDate")) {
            throw new InvalidTripDraftRequestException();
        }
        JsonNode name = root.get("tripName");
        JsonNode codes = root.get("regionCodes");
        if ((!name.isNull() && !name.isTextual()) || !codes.isArray()) {
            throw new InvalidTripDraftRequestException();
        }
        List<String> regionCodes = new ArrayList<>();
        for (JsonNode code : codes) {
            if (!code.isTextual()) throw new InvalidTripDraftRequestException();
            regionCodes.add(code.asText());
        }
        return new TripDraftSaveRequest(name.isNull() ? null : name.asText(), regionCodes,
                date(root.get("startDate")), date(root.get("endDate")));
    }

    private static LocalDate date(JsonNode node) {
        if (node.isNull()) return null;
        if (!node.isTextual()) throw new InvalidTripDraftRequestException();
        try {
            return LocalDate.parse(node.asText());
        } catch (RuntimeException e) {
            throw new InvalidTripDraftRequestException();
        }
    }
}
