package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;
import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatCode;

class WorkflowTraceServiceTest {
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private final WorkflowTraceService traces = new WorkflowTraceService(mock(Tracer.class));

    @Test
    void Kafka_Header는_샘플링과_state를_보존하고_중복_부모를_거부한다() {
        var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        String parent = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";
        headers.add("traceparent", parent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.add("tracestate", "yeodam=synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(traces.fromHeaders(headers)).isEqualTo(new WorkflowTraceCarrier(parent, "yeodam=synthetic"));
        headers.add("traceparent", parent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(traces.fromHeaders(headers).traceparent()).isNull();
    }

    @Test
    void 변환_Span은_고정_이름과_저장된_부모를_사용하고_실패로_종료한다() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class);
        Span span = mock(Span.class);
        when(tracer.spanBuilder("photo.convert")).thenReturn(builder);
        when(builder.setParent(any(Context.class))).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        var service = new WorkflowTraceService(tracer);
        service.finish(service.startConversion(new WorkflowTraceCarrier(
                "00-" + TRACE_ID + "-" + SPAN_ID + "-01", null)), "error");
        var parent = ArgumentCaptor.forClass(Context.class);
        verify(builder).setParent(parent.capture());
        assertThat(Span.fromContext(parent.getValue()).getSpanContext().getSpanId()).isEqualTo(SPAN_ID);
        verify(span).setAttribute("yeodam.result", "error");
        verify(span).setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
        verify(span).end();
    }

    @Test
    void 유효한_Context를_W3C_carrier로_저장하고_복원한다() {
        SpanContext parent = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(),
                TraceState.builder().put("yeodam", "synthetic").build());
        WorkflowTraceCarrier carrier;
        try (Scope scope = Span.wrap(parent).makeCurrent()) {
            carrier = traces.capture();
        }
        assertThat(carrier.traceparent()).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(carrier.tracestate()).isEqualTo("yeodam=synthetic");
        SpanContext restored = Span.fromContext(traces.restore(carrier)).getSpanContext();
        assertThat(restored.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(restored.getSpanId()).isEqualTo(SPAN_ID);
        assertThat(restored.isRemote()).isTrue();
        assertThat(restored.isSampled()).isTrue();
    }

    @Test
    void 샘플링_미선택_부모도_보존한다() {
        SpanContext parent = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());
        try (Scope scope = Span.wrap(parent).makeCurrent()) {
            WorkflowTraceCarrier carrier = traces.capture();
            assertThat(carrier.traceparent()).endsWith("-00");
            assertThat(Span.fromContext(traces.restore(carrier)).getSpanContext().isSampled()).isFalse();
        }
    }

    @Test
    void 결과_적용_Span은_호출자_대신_저장된_carrier를_정확한_부모로_사용한다() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class);
        Span started = mock(Span.class);
        when(tracer.spanBuilder("trip.result.apply")).thenReturn(builder);
        when(builder.setParent(any(Context.class))).thenReturn(builder);
        when(builder.startSpan()).thenReturn(started);
        WorkflowTraceService service = new WorkflowTraceService(tracer);
        WorkflowTraceCarrier carrier = new WorkflowTraceCarrier(
                "00-" + TRACE_ID + "-" + SPAN_ID + "-01", "yeodam=synthetic");
        SpanContext caller = SpanContext.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope scope = Span.wrap(caller).makeCurrent()) {
            assertThat(service.startResultApplication(carrier)).isSameAs(started);
            assertThat(Span.current().getSpanContext()).isEqualTo(caller);
        }
        ArgumentCaptor<Context> parent = ArgumentCaptor.forClass(Context.class);
        verify(builder).setParent(parent.capture());
        SpanContext restored = Span.fromContext(parent.getValue()).getSpanContext();
        assertThat(restored.isValid()).isTrue();
        assertThat(restored.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(restored.getSpanId()).isEqualTo(SPAN_ID);
        assertThat(restored.isRemote()).isTrue();
        assertThat(restored.isSampled()).isTrue();
        assertThat(restored.getTraceState().get("yeodam")).isEqualTo("synthetic");
    }

    @Test
    void 부모가_없으면_NULL_carrier를_반환한다() {
        try (Scope scope = Context.root().makeCurrent()) {
            assertThat(traces.capture()).isEqualTo(new WorkflowTraceCarrier(null, null));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"})
    void 잘못된_부모는_호출자_Context를_상속하지_않는다(String traceparent) {
        SpanContext caller = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope scope = Span.wrap(caller).makeCurrent()) {
            Context restored = traces.restore(new WorkflowTraceCarrier(traceparent, "yeodam=synthetic"));
            assertThat(Span.fromContext(restored).getSpanContext().isValid()).isFalse();
            assertThat(Span.current().getSpanContext()).isEqualTo(caller);
        }
    }

    @Test
    void 길이가_초과한_state만_버리고_부모는_유지한다() {
        WorkflowTraceCarrier carrier = new WorkflowTraceCarrier(
                "00-" + TRACE_ID + "-" + SPAN_ID + "-01", "x=" + "a".repeat(511));
        SpanContext restored = Span.fromContext(traces.restore(carrier)).getSpanContext();
        assertThat(restored.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(restored.getTraceState()).isEqualTo(TraceState.getDefault());
    }

    @Test
    void 최대_512자_state는_그대로_보존한다() {
        String state = "a=" + "a".repeat(254) + ",b=" + "b".repeat(253);
        WorkflowTraceCarrier carrier = new WorkflowTraceCarrier(
                "00-" + TRACE_ID + "-" + SPAN_ID + "-01", state);
        assertThat(state).hasSize(512);
        try (Scope scope = traces.restore(carrier).makeCurrent()) {
            assertThat(traces.capture().tracestate()).isEqualTo(state);
        }
    }

    @Test
    void tracer_장애는_업무로_전파하지_않는다() {
        assertThat(traces.startResultApplication(null).getSpanContext().isValid()).isFalse();
    }

    @Test
    void Span_진입과_종료_장애가_업무_실패로_전파되지_않는다() {
        Span span = mock(Span.class);
        RuntimeException failure = new IllegalStateException(
                IntegrationInternalErrorMessage.WORKFLOW_TRACE_UNAVAILABLE.message());
        when(span.makeCurrent()).thenThrow(failure);
        doThrow(failure).when(span).end();
        try (Scope scope = traces.makeCurrent(span)) {
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
        assertThatCode(() -> traces.end(span)).doesNotThrowAnyException();
    }

    @Test
    void Scope_종료_장애가_업무_결과를_덮어쓰지_않는다() {
        Span span = mock(Span.class);
        Scope failing = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(failing);
        doThrow(new IllegalStateException(IntegrationInternalErrorMessage.WORKFLOW_TRACE_UNAVAILABLE.message()))
                .when(failing).close();
        assertThatCode(() -> traces.makeCurrent(span).close()).doesNotThrowAnyException();
    }
}
