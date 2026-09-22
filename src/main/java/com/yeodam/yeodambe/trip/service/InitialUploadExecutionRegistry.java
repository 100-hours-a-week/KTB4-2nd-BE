package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InitialUploadExecutionRegistry {
    private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

    public String reserve(Long tripId) {
        String executionId = UUID.randomUUID().toString();
        if (executions.putIfAbsent(tripId, new Execution(executionId, State.RESERVED)) != null) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        return executionId;
    }

    public boolean isCurrent(Long tripId, String executionId) {
        Execution execution = executions.get(tripId);
        return execution != null && execution.id().equals(executionId);
    }

    public boolean markAnalysisStarted(Long tripId, String executionId) {
        AtomicBoolean started = new AtomicBoolean();
        executions.computeIfPresent(tripId, (ignored, current) ->
                {
                    if (!current.id().equals(executionId) || current.state() == State.CANCELED) return current;
                    started.set(true);
                    return new Execution(executionId, State.STARTED);
                });
        return started.get();
    }

    public boolean isAnalysisStarted(Long tripId) {
        Execution execution = executions.get(tripId);
        return execution != null && execution.state() == State.STARTED;
    }

    public boolean cancel(Long tripId) {
        AtomicBoolean started = new AtomicBoolean();
        executions.computeIfPresent(tripId, (ignored, current) -> {
            started.set(current.state() == State.STARTED);
            return new Execution(current.id(), State.CANCELED);
        });
        return started.get();
    }

    public void release(Long tripId, String executionId) {
        executions.computeIfPresent(tripId, (ignored, current) ->
                current.id().equals(executionId) ? null : current);
    }

    private record Execution(String id, State state) {
    }

    private enum State {
        RESERVED,
        STARTED,
        CANCELED
    }
}
