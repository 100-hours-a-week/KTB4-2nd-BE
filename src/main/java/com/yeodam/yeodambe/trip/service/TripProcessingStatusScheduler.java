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
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;

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
    private final TripAnalysisExecutionRepository analyses;

    @Scheduled(
            initialDelayString = "${app.trip.worker-initial-delay-ms:0}",
            fixedDelay = 2_000
    )
    public void synchronizePendingExecutions() {
        for (TripCreationExecution execution : creationExecutions.findAwaitingAiStatus()) {
            synchronize(execution.getTripId(), execution.getExecutionId(), () ->
                    statusService.synchronizeCreation(execution.getExecutionId()));
        }

        for (AdditionalAttachmentExecution execution : additionalExecutions.findAwaitingAiStatus()) {
            synchronize(execution.getTripId(), execution.getExecutionId(), () ->
                    statusService.synchronizeAddition(execution.getExecutionId()));
        }
        for (TripAnalysisExecution execution : analyses.findAwaitingAiStatus()) {
            synchronize(execution.getTripId(), execution.getExecutionId(), () ->
                    statusService.synchronizeAnalysis(execution.getExecutionId()));
        }
    }

    private void synchronize(Long tripId, String executionId, Runnable synchronize) {
        try {
            synchronize.run();
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
