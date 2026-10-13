package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.trip.event.handler.TripCreateRequestEventHandler;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import com.yeodam.yeodambe.trip.service.response.TripKafkaDeliveryClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOutboxPublisher {
    private final InitialAttachmentUploadBatchRepository batches;
    private final TripCreationExecutionRepository executions;
    private final InitialAttachmentUploadTransactionService transactions;
    private final TripCreateRequestEventHandler requests;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.outbox-delay-ms:5000}")
    public void publishPending() {
        for (var batch : batches.findPendingDelivery(transactions.databaseTime(), PageRequest.of(0, 20))) {
            transactions.claimDelivery(batch.getId()).ifPresent(this::publish);
        }

        for (var execution : executions.findPendingProcesses(transactions.databaseTime(), PageRequest.of(0, 20))) {
            transactions.claimProcess(execution.getExecutionId()).ifPresent(this::publish);
        }
    }

    private void publish(TripKafkaDeliveryClaim claim) {
        try {
            requests.publish(claim.tripId().toString(), claim.payload()).get(30, TimeUnit.SECONDS);
            transactions.finishDelivery(claim, true);

        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.warn("Kafka 사진 요청 전송 재시도: executionId={}, outboxId={}, attempt={}, cause={}",
                    claim.executionId(), claim.outboxId(), claim.attempt(), failure.getClass().getSimpleName());
            transactions.finishDelivery(claim, false);
        }
    }
}
