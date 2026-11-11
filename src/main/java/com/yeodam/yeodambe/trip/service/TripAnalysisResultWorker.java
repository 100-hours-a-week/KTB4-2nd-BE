package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.trip.result-worker-enabled", havingValue = "true", matchIfMissing = true)
public class TripAnalysisResultWorker {
    private final TripAnalysisResultService results;
    private final TripPlaceNameService placeNames;
    private final TripMutationCoordinator mutations;
    private final WorkflowTraceService traces;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.result-delay-ms:5000}")
    public void applyPendingResults() {
        Optional<TripAnalysisResultClaim> pending = claimNext();
        if (pending.isEmpty()) {
            return;
        }
        TripAnalysisResultClaim claim = pending.get();
        Span span = traces.startResultApplication(claim.traceContext());
        try (Scope traceScope = traces.makeCurrent(span);
             WorkflowMdcScope scope = WorkflowMdcScope.job("trip_creation", claim.tripId(), claim.executionId())) {
            applyClaim(claim);
        } finally {
            traces.end(span);
        }
    }

    private void applyClaim(TripAnalysisResultClaim claim) {
        boolean validated = false;
        try {
            if (!mutations.execute(claim.tripId(), () -> results.startResultAttempt(claim))) {
                return;
            }
            try (WorkflowLog.Stage timing = startResultApplicationLogging(claim.attempt())) {
                JsonNode result = results.validateForApplication(claim);
                validated = true;
                Map<String, String> names = placeNames.resolve(claim.tripId(), claim.executionId(), result,
                        () -> mutations.execute(claim.tripId(), () -> results.renewResultLease(claim)));
                mutations.run(claim.tripId(), () -> results.saveKafkaCompleted(claim, result, names));
                timing.success();
            }
        } catch (RuntimeException failure) {
            if (failure instanceof TripEditLockRejectedException
                    || failure instanceof CancellationException) {
                return;
            }
            logResultApplicationFailure(claim.tripId(), claim.executionId(), claim.attempt(), validated, failure);
            boolean permanent = !validated && !(failure instanceof DataAccessException);
            String reason = permanent && failure instanceof IllegalStateException
                    ? failure.getMessage() : failure.getClass().getSimpleName();
            if (reason == null || reason.length() > 255) {
                reason = ErrorMessage.AI_RESULT_APPLICATION_FAILED.name();
            }
            String failureReason = reason;
            mutations.run(claim.tripId(), () -> results.failResult(claim, permanent, failureReason));
        }
    }

    private Optional<TripAnalysisResultClaim> claimNext() {
        for (TripCreationExecution candidate : results.findResultClaimCandidates()) {
            try {
                Optional<TripAnalysisResultClaim> claim = mutations.execute(
                        candidate.getTripId(), () -> results.claimResult(candidate.getExecutionId()));
                if (claim.isPresent()) {
                    return claim;
                }
            } catch (TripEditLockRejectedException failure) {
                if (failure.reason() != TripEditLockRejectedException.Reason.CONTENDED) {
                    throw failure;
                }
            }
        }
        return Optional.empty();
    }

    private void logResultApplicationFailure(
            Long tripId,
            String executionId,
            int attempts,
            boolean validated,
            RuntimeException failure
    ) {
        log.atWarn().addKeyValue("workflow", "trip_creation")
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
