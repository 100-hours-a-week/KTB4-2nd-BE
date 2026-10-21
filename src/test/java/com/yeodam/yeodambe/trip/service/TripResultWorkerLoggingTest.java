package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripResultWorkerLoggingTest {
    @Test
    void recoveredCreationClaimRestoresJobContextAndCallerAfterFailure() {
        var results = mock(TripAnalysisResultService.class);
        var names = mock(TripPlaceNameService.class);
        var claim = new TripAnalysisResultClaim("job-1", 42L, 1L, "token", "{}", 2);
        when(results.claimResult()).thenReturn(Optional.of(claim));
        when(results.startResultAttempt(claim)).thenReturn(true);
        AtomicReference<String> observed = new AtomicReference<>();
        when(results.validateForApplication(claim)).thenAnswer(call -> {
            observed.set(MDC.get("job_id"));
            throw new IllegalStateException("invalid");
        });
        MDC.put("request_id", "caller");
        try {
            new TripAnalysisResultWorker(results, names).applyPendingResults();
            assertThat(observed.get()).isEqualTo("job-1");
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", "caller"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void recoveredAdditionalClaimKeepsItsWorkflowDuringGeocoding() {
        var results = mock(AdditionalAttachmentKafkaResultService.class);
        var names = mock(TripPlaceNameService.class);
        var claim = new TripAnalysisResultClaim("job-2", 42L, 1L, "token", "{}", 1);
        var result = new ObjectMapper().readTree("{}");
        when(results.claim()).thenReturn(Optional.of(claim));
        when(results.start(claim)).thenReturn(true);
        when(results.validate(claim)).thenReturn(result);
        AtomicReference<String> observed = new AtomicReference<>();
        when(names.resolve(eq(42L), eq("job-2"), eq(result), any())).thenAnswer(call -> {
            observed.set(MDC.get("workflow"));
            return Map.of();
        });
        new AdditionalAttachmentResultWorker(results, names).applyPendingResults();
        assertThat(observed.get()).isEqualTo("trip_reanalysis");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
