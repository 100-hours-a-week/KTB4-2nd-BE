package com.yeodam.yeodambe.common.logging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class WorkflowMdcScope implements AutoCloseable {
    private final Map<String, String> previous;

    private WorkflowMdcScope(Map<String, String> context) {
        previous = MDC.getCopyOfContextMap();
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    public static WorkflowMdcScope open(Map<String, String> context) {
        return new WorkflowMdcScope(context);
    }

    public static WorkflowMdcScope job(String workflow, Long tripId, String jobId) {
        return job(workflow, tripId, jobId, MDC.get("request_id"));
    }

    public static WorkflowMdcScope job(String workflow, Long tripId, String jobId, String requestId) {
        Map<String, String> context = new HashMap<>();
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current != null) {
            context.putAll(current);
        }
        if (!jobId.equals(context.get("job_id"))) {
            for (String key : java.util.List.of("generation_id", "batch_no", "batch_id", "item_id", "attempts")) {
                context.remove(key);
            }
        }
        context.remove("request_id");
        if (requestId != null && !requestId.equals(jobId)) {
            context.put("request_id", requestId);
        }
        context.put("workflow", workflow);
        context.put("trip_id", tripId.toString());
        context.put("job_id", jobId);
        return open(context);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
