package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InitialUploadExecutionRegistry {
    private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

    public String reserve(Long tripId) {
        String executionId = UUID.randomUUID().toString();
        if (executions.putIfAbsent(tripId, new Execution(executionId, false)) != null) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        return executionId;
    }

    public boolean isCurrent(Long tripId, String executionId) {
        Execution execution = executions.get(tripId);
        return execution != null && execution.id().equals(executionId);
    }

    public void markAnalysisStarted(Long tripId, String executionId) {
        executions.computeIfPresent(tripId, (ignored, current) ->
                current.id().equals(executionId) ? new Execution(executionId, true) : current);
    }

    public boolean isAnalysisStarted(Long tripId) {
        Execution execution = executions.get(tripId);
        return execution != null && execution.analysisStarted();
    }

    public void release(Long tripId, String executionId) {
        executions.computeIfPresent(tripId, (ignored, current) ->
                current.id().equals(executionId) ? null : current);
    }

    private record Execution(String id, boolean analysisStarted) {
    }
}
