package com.yeodam.yeodambe.trip.service;

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
        if (pending.isEmpty()) return;
        var claim = pending.get();
        boolean validated = false;
        try {
            if (!results.start(claim)) return;
            var result = results.validate(claim);
            validated = true;
            var names = placeNames.resolve(claim.tripId(), claim.executionId(), result, () -> results.renew(claim));
            results.complete(claim, result, names);
        } catch (RuntimeException failure) {
            log.warn("Additional result application failed: executionId={}", claim.executionId(), failure);
            boolean permanent = !validated && !(failure instanceof DataAccessException);
            String reason = failure.getClass().getSimpleName();
            results.fail(claim, permanent, reason);
        }
    }
}
