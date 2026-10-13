package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.trip.event.handler.TripCreateRequestEventHandler;
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
    private final TripCreateRequestEventHandler requests;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.outbox-delay-ms:5000}")
    public void publishPending() {
        for (int index = 0; index < 20; index++) {
            var pending = delivery.claim();
            if (pending.isEmpty()) return;
            var claim = pending.get();
            var previous = MDC.getCopyOfContextMap();
            try {
                MDC.put("request_id", claim.requestId());
                requests.publish(claim.tripId().toString(), claim.payload()).get(30, TimeUnit.SECONDS);
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
