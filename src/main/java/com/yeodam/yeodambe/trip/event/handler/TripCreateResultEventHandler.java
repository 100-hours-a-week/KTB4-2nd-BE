package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TripCreateResultEventHandler implements EventHandler<TripCreateResultEvent> {
    private final TripAnalysisResultService results;
    private final ObjectMapper json;

    @Override
    public EventType eventType() {
        return EventType.TRIP_CREATE_RESULT;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.trip-create-result}",
            groupId = "${app.trip.result-consumer-group:yeodam-trip-results}",
            autoStartup = "${app.trip.result-listener-enabled:false}",
            containerFactory = "tripResultKafkaListenerContainerFactory"
    )
    public void consume(String rawPayload) {
        TripCreateResultEvent event;
        try {
            var root = json.readTree(rawPayload);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("TRIP_RESULT_TRANSPORT_INVALID");
            }
            if ("story_result".equals(root.path("type").asString())) {
                return;
            }
            if (!root.path("trip_id").isIntegralNumber() || !root.path("trip_id").canConvertToLong()
                    || !root.path("execution_id").isString() || !root.path("status").isString()) {
                throw new IllegalArgumentException("TRIP_RESULT_TRANSPORT_INVALID");
            }
            event = json.readerFor(TripCreateResultEvent.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(rawPayload);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("TRIP_RESULT_TRANSPORT_INVALID");
        }
        handle(event, rawPayload);
    }

    @Override
    public void handle(TripCreateResultEvent event) {
        Assert.notNull(event,
                EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (supports(event.eventType())) {
            handle(event, json.writeValueAsString(event));
        }
    }

    public void handle(
            TripCreateResultEvent event,
            String rawPayload
    ) {
        results.receiveKafkaResult(event, rawPayload);
    }
}
