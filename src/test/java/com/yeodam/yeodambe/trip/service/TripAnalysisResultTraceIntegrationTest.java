package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripAnalysisResultTraceIntegrationTest {
    private final TripAnalysisResultService results = mock(TripAnalysisResultService.class);
    private final TripPlaceNameService names = mock(TripPlaceNameService.class);
    private final WorkflowTraceService traces = mock(WorkflowTraceService.class);
    private final TripAnalysisResultWorker worker = new TripAnalysisResultWorker(
            results, names, TripMutationTestFixture.direct(), traces);

    @Test
    void 성공과_실패_처리에서_각_작업_Context를_유지하고_연속_작업_사이에_복원한다() {
        TripAnalysisResultClaim first = prepare("first", "11111111111111111111111111111111");
        TripAnalysisResultClaim second = prepare("second", "22222222222222222222222222222222");
        when(results.findResultClaimCandidates())
                .thenReturn(List.of(new TripCreationExecution("first", 7L, 1L, 1)))
                .thenReturn(List.of(new TripCreationExecution("second", 7L, 1L, 1)));
        when(results.validateForApplication(first)).thenThrow(
                new IllegalStateException(TripUploadTestErrorMessage.INVALID_RESULT.message()));
        AtomicReference<String> failedTrace = new AtomicReference<>();
        AtomicReference<String> failedJob = new AtomicReference<>();
        doAnswer(call -> {
            failedTrace.set(Span.current().getSpanContext().getTraceId());
            failedJob.set(MDC.get("job_id"));
            return null;
        }).when(results).failResult(eq(first), eq(true), any());
        JsonNode result = new ObjectMapper().readTree("{}");
        when(results.validateForApplication(second)).thenReturn(result);
        when(names.resolve(eq(7L), eq("second"), eq(result), any())).thenReturn(Map.of());
        AtomicReference<String> completedTrace = new AtomicReference<>();
        doAnswer(call -> {
            completedTrace.set(Span.current().getSpanContext().getTraceId());
            return null;
        }).when(results).saveKafkaCompleted(second, result, Map.of());

        worker.applyPendingResults();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        worker.applyPendingResults();
        assertThat(failedTrace.get()).isEqualTo("11111111111111111111111111111111");
        assertThat(failedJob.get()).isEqualTo("first");
        assertThat(completedTrace.get()).isEqualTo("22222222222222222222222222222222");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        verify(traces.startResultApplication(first.traceContext())).end();
        verify(traces.startResultApplication(second.traceContext())).end();
    }

    private TripAnalysisResultClaim prepare(String jobId, String traceId) {
        when(traces.makeCurrent(any())).thenCallRealMethod();
        doCallRealMethod().when(traces).end(any());
        WorkflowTraceCarrier carrier = new WorkflowTraceCarrier(
                "00-" + traceId + "-00f067aa0ba902b7-01", null);
        TripAnalysisResultClaim claim = new TripAnalysisResultClaim(jobId, 7L, 1L, "token", "{}", 1, carrier);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(span.getSpanContext()).thenReturn(SpanContext.create(traceId, "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.getDefault()));
        when(traces.startResultApplication(carrier)).thenReturn(span);
        when(results.claimResult(jobId)).thenReturn(Optional.of(claim));
        when(results.startResultAttempt(claim)).thenReturn(true);
        return claim;
    }
}
