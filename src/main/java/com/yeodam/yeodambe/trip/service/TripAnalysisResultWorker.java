package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TripAnalysisResultWorker {
    private final TripAnalysisResultService results;
    private final TripPlaceNameService placeNames;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.result-delay-ms:5000}")
    public void applyPendingResults() {
        var pending = results.claimResult();
        if (pending.isEmpty()) {
            return;
        }
        var claim = pending.get();
        boolean validated = false;
        try {
            if (!results.startResultAttempt(claim)) {
                return;
            }
            var result = results.validateForApplication(claim);
            validated = true;
            var names = placeNames.resolve(claim.tripId(), claim.executionId(), result,
                    () -> results.renewResultLease(claim));
            results.saveKafkaCompleted(claim, result, names);
        } catch (RuntimeException failure) {
            log.warn("사진 정리 결과 반영 실패: executionId={}, stage={}, attempt={}, cause={}",
                    claim.executionId(), validated ? "result_persist" : "result_validate",
                    claim.attempt(), failure.getClass().getSimpleName());
            boolean permanent = !validated && !(failure instanceof DataAccessException);
            String reason = permanent && failure instanceof IllegalStateException
                    ? failure.getMessage() : failure.getClass().getSimpleName();
            if (reason == null || reason.length() > 255) {
                reason = "AI_RESULT_APPLICATION_FAILED";
            }
            results.failResult(claim, permanent, reason);
        }
    }
}
