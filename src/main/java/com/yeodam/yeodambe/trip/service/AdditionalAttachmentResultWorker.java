package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentResultWorker {
    private final AdditionalAttachmentKafkaResultService results;
    private final TripPlaceNameService placeNames;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.result-delay-ms:5000}")
    public void applyPendingResults() {
        results.expire();
        var pending = results.claim();
        if (pending.isEmpty()) {
            return;
        }
        var claim = pending.get();
        boolean validated = false;
        try (var scope = WorkflowMdcScope.job("trip_reanalysis", claim.tripId(), claim.executionId())) {
            if (!results.start(claim)) {
                return;
            }
            try (var timing = startResultApplicationLogging(claim.attempt())) {
                var result = results.validate(claim);
                validated = true;
                var names = placeNames.resolve(claim.tripId(), claim.executionId(), result, () -> results.renew(claim));
                results.complete(claim, result, names);
                timing.success();
            }
        } catch (RuntimeException failure) {
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
                .log("사진 정리 결과 반영을 완료하지 못했습니다.");
    }

    private WorkflowLog.Stage startResultApplicationLogging(int attempts) {
        return WorkflowLog.stage("result_application").field("attempts", attempts);
    }
}
