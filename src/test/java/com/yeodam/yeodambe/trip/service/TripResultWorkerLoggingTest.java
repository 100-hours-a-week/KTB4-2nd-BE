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
    void 복구한_생성_선점은_실패_후_작업_문맥과_호출자_문맥을_복원한다() {
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
    void 복구한_추가_선점은_지오코딩_중_워크플로를_유지한다() {
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
