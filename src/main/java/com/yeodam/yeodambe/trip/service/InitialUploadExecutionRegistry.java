package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InitialUploadExecutionRegistry {
    private final ConcurrentHashMap<Long, String> executions = new ConcurrentHashMap<>();

    public String reserve(Long tripId) {
        String executionId = UUID.randomUUID().toString();
        if (executions.putIfAbsent(tripId, executionId) != null) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        return executionId;
    }

    public boolean isCurrent(Long tripId, String executionId) {
        return executionId != null && executionId.equals(executions.get(tripId));
    }

    public void release(Long tripId, String executionId) {
        executions.remove(tripId, executionId);
    }
}
