package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.trip.status-polling-enabled",
        havingValue = "true"
)
public class TripProcessingStatusScheduler {

    private final TripCreationExecutionRepository creationExecutions;
    private final AdditionalAttachmentExecutionRepository additionalExecutions;
    private final TripProcessingStatusService statusService;

    @Scheduled(
            initialDelayString = "${app.trip.worker-initial-delay-ms:0}",
            fixedDelay = 2_000
    )
    public void synchronizePendingExecutions() {
        for (TripCreationExecution execution : creationExecutions.findAwaitingAiStatus()) {
            synchronize(execution.getTripId(), execution.getUserId(), execution.getExecutionId());
        }

        for (AdditionalAttachmentExecution execution : additionalExecutions.findAwaitingAiStatus()) {
            synchronize(execution.getTripId(), execution.getUserId(), execution.getExecutionId());
        }
    }

    private void synchronize(Long tripId, Long userId, String executionId) {
        try {
            statusService.synchronizeStatus(tripId, userId);
        } catch (RuntimeException failure) {
            log.atWarn()
                    .addKeyValue("event", "trip_status_sync")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("failure", failure.getClass().getName())
                    .log(TripInternalErrorMessage.PROCESSING_STATUS_SYNC_FAILED.message());
        }
    }
}
