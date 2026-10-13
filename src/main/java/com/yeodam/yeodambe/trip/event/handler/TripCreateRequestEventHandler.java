package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class TripCreateRequestEventHandler implements EventHandler<TripCreateRequestEvent> {
    private final InitialAttachmentUploadTransactionService transactions;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaTopicProperties topics;

    @Override
    public EventType eventType() {
        return EventType.TRIP_CREATE_REQUEST;
    }

    @Override
    public void handle(TripCreateRequestEvent event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (!supports(event.eventType())) {
            return;
        }
        transactions.enqueueRequest(event);
    }

    public CompletableFuture<SendResult<String, String>> publish(
            String key,
            String payload
    ) {
        return kafka.send(topics.topicFor(eventType()), key, payload);
    }
}
