package com.yeodam.yeodambe.integration.service.response;

public record WorkflowTraceCarrier(
        String traceparent,
        String tracestate
) {
}
