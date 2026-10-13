package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import java.nio.charset.StandardCharsets;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentOutboxPublisher {
    private final AdditionalAttachmentDeliveryService delivery;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaTopicProperties topics;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.outbox-delay-ms:5000}")
    public void publishPending() {
        for (int index = 0; index < 20; index++) {
            var pending = delivery.claim();
            if (pending.isEmpty()) return;
            var claim = pending.get();
            var previous = MDC.getCopyOfContextMap();
            try {
                MDC.put("request_id", claim.requestId());
                var record = new ProducerRecord<String, String>(topics.topicFor(EventType.TRIP_UPDATE_REQUEST),
                        claim.tripId().toString(), claim.payload());
                record.headers().add("X-Request-ID", claim.requestId().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(30, TimeUnit.SECONDS);
                delivery.finish(claim, true, null);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Additional Kafka publication failed: executionId={}", claim.executionId(), failure);
                delivery.finish(claim, false, failure.getClass().getSimpleName());
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        }
    }
}
