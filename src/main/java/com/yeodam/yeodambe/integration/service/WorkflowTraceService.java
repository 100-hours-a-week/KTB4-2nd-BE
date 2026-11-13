package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WorkflowTraceService {
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
    private final Tracer tracer;

    public WorkflowTraceService() {
        this(GlobalOpenTelemetry.getTracer("com.yeodam.yeodambe.workflow"));
    }

    WorkflowTraceService(Tracer tracer) {
        this.tracer = tracer;
    }

    public WorkflowTraceCarrier capture() {
        try {
            Map<String, String> carrier = new HashMap<>();
            PROPAGATOR.inject(Context.current(), carrier, Map::put);
            Context valid = restore(new WorkflowTraceCarrier(carrier.get("traceparent"), carrier.get("tracestate")));
            Map<String, String> normalized = new HashMap<>();
            PROPAGATOR.inject(valid, normalized, Map::put);
            return new WorkflowTraceCarrier(normalized.get("traceparent"), normalized.get("tracestate"));
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return new WorkflowTraceCarrier(null, null);
        }
    }

    public Context restore(WorkflowTraceCarrier carrier) {
        try {
            if (carrier == null || carrier.traceparent() == null
                    || carrier.traceparent().length() != 55 || !carrier.traceparent().startsWith("00-")) {
                return Context.root();
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", carrier.traceparent());
            if (carrier.tracestate() != null && carrier.tracestate().length() <= 512) {
                headers.put("tracestate", carrier.tracestate());
            }
            return PROPAGATOR.extract(Context.root(), headers, GETTER);
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return Context.root();
        }
    }

    public WorkflowTraceCarrier fromHeaders(Headers headers) {
        if (headers == null) return new WorkflowTraceCarrier(null, null);
        try {
            Map<String, String> values = new HashMap<>();
            for (String key : List.of("traceparent", "tracestate")) {
                var entries = headers.headers(key).iterator();
                if (!entries.hasNext()) continue;
                byte[] value = entries.next().value();
                if (entries.hasNext() || value == null || value.length > (key.equals("traceparent") ? 55 : 512)) {
                    return new WorkflowTraceCarrier(null, null);
                }
                values.put(key, new String(value, StandardCharsets.UTF_8));
            }
            Map<String, String> normalized = new HashMap<>();
            PROPAGATOR.inject(restore(new WorkflowTraceCarrier(values.get("traceparent"), values.get("tracestate"))),
                    normalized, Map::put);
            return new WorkflowTraceCarrier(normalized.get("traceparent"), normalized.get("tracestate"));
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return new WorkflowTraceCarrier(null, null);
        }
    }

    public Span startConversion(WorkflowTraceCarrier carrier) {
        return start("photo.convert", carrier);
    }

    public Span startProcessDispatch() {
        return start("trip.process.dispatch", capture());
    }

    public Scope openContext(Context context) {
        try {
            Scope scope = context.makeCurrent();
            return () -> {
                try { scope.close(); } catch (RuntimeException failure) { logUnavailable(failure); }
            };
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return Context.root().makeCurrent();
        }
    }

    public void finish(Span span, String result) {
        try {
            span.setAttribute("yeodam.result", result);
            if ("error".equals(result)) span.setStatus(StatusCode.ERROR);
        } catch (RuntimeException failure) {
            logUnavailable(failure);
        } finally {
            end(span);
        }
    }

    private Span start(String name, WorkflowTraceCarrier carrier) {
        try {
            return tracer.spanBuilder(name).setParent(restore(carrier)).startSpan();
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return Span.getInvalid();
        }
    }

    public Span startResultApplication(WorkflowTraceCarrier carrier) {
        try {
            return tracer.spanBuilder("trip.result.apply").setParent(restore(carrier)).startSpan();
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return Span.getInvalid();
        }
    }

    public Scope makeCurrent(Span span) {
        try {
            Scope scope = span.makeCurrent();
            return () -> {
                try {
                    scope.close();
                } catch (RuntimeException failure) {
                    logUnavailable(failure);
                }
            };
        } catch (RuntimeException failure) {
            logUnavailable(failure);
            return Context.root().makeCurrent();
        }
    }

    public void end(Span span) {
        try {
            span.end();
        } catch (RuntimeException failure) {
            logUnavailable(failure);
        }
    }

    private void logUnavailable(RuntimeException failure) {
        log.atWarn().addKeyValue("event", "workflow_trace_unavailable")
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .log(IntegrationInternalErrorMessage.WORKFLOW_TRACE_UNAVAILABLE.message());
    }
}
