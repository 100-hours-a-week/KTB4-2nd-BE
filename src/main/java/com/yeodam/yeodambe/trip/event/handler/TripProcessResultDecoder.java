package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

final class TripProcessResultDecoder {
    private TripProcessResultDecoder() {}

    static TripCreateResultEvent decode(ObjectMapper json, String rawPayload) {
        TripCreateResultEvent event;
        try {
            var root = json.readTree(rawPayload);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(ErrorMessage.TRIP_RESULT_TRANSPORT_INVALID.name());
            }
            if ("story_result".equals(root.path("type").asString())) {
                return null;
            }
            if (!root.path("trip_id").isIntegralNumber() || !root.path("trip_id").canConvertToLong()
                    || !root.path("execution_id").isString() || !root.path("status").isString()) {
                throw new IllegalArgumentException(ErrorMessage.TRIP_RESULT_TRANSPORT_INVALID.name());
            }
            event = json.readerFor(TripCreateResultEvent.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(rawPayload);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(ErrorMessage.TRIP_RESULT_TRANSPORT_INVALID.name());
        }
        return event;
    }
}
