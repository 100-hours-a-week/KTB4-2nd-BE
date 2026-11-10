package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate.Cursor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class AttachmentConversionRecoveryService implements SchedulingConfigurer {
    private final AttachmentConversionProperties properties;
    private final AttachmentConversionService transactions;
    private final AtomicBoolean running = new AtomicBoolean();
    private Cursor cursor;

    public void reconcilePendingRequests() {
        if (!properties.recoveryExecutionEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcile();
        } catch (DataAccessException failure) {
            log.warn(TripInternalErrorMessage.CONVERSION_RECOVERY_RECONCILIATION_FAILED.message(),
                    failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    private void reconcile() {
        var recovery = properties.recovery();
        int reissues = 0;
        for (int page = 0; page < recovery.maxPagesPerRun() && reissues < recovery.maxReissuesPerRun(); page++) {
            var candidates = transactions.findRecoveryCandidates(cursor, recovery.pageSize(), recovery.interval());
            if (candidates.isEmpty()) {
                cursor = null;
                return;
            }
            for (var candidate : candidates) {
                if (reissues >= recovery.maxReissuesPerRun()) {
                    return;
                }
                if (transactions.renotifyIfEligible(candidate, recovery.interval())) {
                    reissues++;
                }
                cursor = new Cursor(candidate.createdAt(), candidate.taskId());
            }
            if (candidates.size() < recovery.pageSize()) {
                cursor = null;
                return;
            }
        }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.recoveryExecutionEnabled()) {
            registrar.addFixedDelayTask(this::reconcilePendingRequests, properties.recovery().interval());
        }
    }
}
