package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.trip.result-worker-enabled", havingValue = "true", matchIfMissing = true)
public class AdditionalAttachmentResultWorker {
    private final TripAnalysisResultApplicationService results;
    private final TripPlaceNameService placeNames;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.result-delay-ms:5000}")
    public void applyPendingResults() {
        results.expire();
        Optional<TripAnalysisResultClaim> pending = results.claim();
        if (pending.isEmpty()) {
            return;
        }
        TripAnalysisResultClaim claim = pending.get();
        boolean validated = false;
        try (WorkflowMdcScope scope = WorkflowMdcScope.job("trip_reanalysis", claim.tripId(), claim.executionId())) {
            if (!results.start(claim)) {
                return;
            }
            try (WorkflowLog.Stage timing = startResultApplicationLogging(claim.attempt())) {
                JsonNode result = results.validate(claim);
                validated = true;
                Map<String, String> names = placeNames.resolve(claim.tripId(), claim.executionId(), result, () -> results.renew(claim));
                results.complete(claim, result, names);
                timing.success();
            }
        } catch (RuntimeException failure) {
            if (failure instanceof TripEditLockRejectedException || failure instanceof CancellationException) {
                return;
            }
            logResultApplicationFailure(claim.tripId(), claim.executionId(), claim.attempt(), validated, failure);
            boolean permanent = !validated && !(failure instanceof DataAccessException);
            String reason = failure.getClass().getSimpleName();
            results.fail(claim, permanent, reason);
        }
    }

    private void logResultApplicationFailure(
            Long tripId,
            String executionId,
            int attempts,
            boolean validated,
            RuntimeException failure
    ) {
        log.atWarn().addKeyValue("workflow", "trip_reanalysis")
                .addKeyValue("event", "result_application_retry")
                .addKeyValue("result", "failure")
                .addKeyValue("trip_id", tripId).addKeyValue("job_id", executionId)
                .addKeyValue("attempts", attempts)
                .addKeyValue("failure_stage", validated ? "result_persist" : "result_validate")
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .log(TripInternalErrorMessage.RESULT_APPLICATION_FAILED.message());
    }

    private WorkflowLog.Stage startResultApplicationLogging(int attempts) {
        return WorkflowLog.stage("result_application").field("attempts", attempts);
    }
}
