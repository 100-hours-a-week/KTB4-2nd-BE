package com.yeodam.yeodambe.integration.service;

import io.opentelemetry.api.trace.Tracer;

public final class WorkflowTraceTestFactory {
    private WorkflowTraceTestFactory() {}
    public static WorkflowTraceService create(Tracer tracer) {
        return new WorkflowTraceService(tracer);
    }
}
