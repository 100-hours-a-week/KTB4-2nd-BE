package com.yeodam.yeodambe.trip.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(Object value, ObjectMapper objectMapper) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }

    public static JsonNode decode(String value, ObjectMapper objectMapper) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException();
        }

        JsonNode node = objectMapper.readTree(Base64.getUrlDecoder().decode(value));
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException();
        }
        return node;
    }
}
