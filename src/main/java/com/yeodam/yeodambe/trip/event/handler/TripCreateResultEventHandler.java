package com.yeodam.yeodambe.trip.event.handler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import com.yeodam.yeodambe.trip.service.TripMutationCoordinator;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!${app.attachment-conversion.operations.enabled:false}")
public class TripCreateResultEventHandler implements EventHandler<TripCreateResultEvent> {
    private final TripAnalysisResultService results;
    private final ObjectMapper json;
    private final TripMutationCoordinator mutations;

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
        TripCreateResultEvent event = TripProcessResultDecoder.decode(json, rawPayload);
        if (event == null) {
            return;
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
        try {
            mutations.run(event.tripId(), () -> results.receiveKafkaResult(event, rawPayload));
        } catch (TripNotFoundException missingTrip) {
            // 존재하지 않는 여행의 결과는 기존 수신 계약대로 무시한다.
        }
    }
}
