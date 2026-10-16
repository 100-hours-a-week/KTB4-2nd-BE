package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.event.TripUpdateResultEvent;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.util.Assert;

@Component
@RequiredArgsConstructor
public class TripUpdateResultEventHandler implements EventHandler<TripUpdateResultEvent> {
    private final AdditionalAttachmentKafkaResultService additionalResults;
    private final ObjectMapper json;

    @KafkaListener(
            topics = "${app.kafka.topics.trip-update-result}",
            groupId = "${KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP:yeodam-trip-update-results}",
            autoStartup = "${app.trip.result-listener-enabled:false}",
            containerFactory = "tripUpdateResultKafkaListenerContainerFactory"
    )
    public void consume(String rawPayload) {
        var event = TripProcessResultDecoder.decode(json, rawPayload);
        if (event != null) additionalResults.receive(event, rawPayload);
    }


    @Override
    public EventType eventType() {
        return EventType.TRIP_UPDATE_RESULT;
    }

    @Override
    public void handle(TripUpdateResultEvent event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (!supports(event.eventType())) {
            return;
        }

        // 미구현 상태에서 정상 반환하면 소비자가 처리 완료로 판단할 수 있다.
        throw new UnsupportedOperationException(
                EventInternalErrorMessage.EVENT_HANDLER_NOT_IMPLEMENTED.message().formatted(eventType()));
    }
}
