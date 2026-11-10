package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression("!${app.attachment-conversion.operations.enabled:false} && ${app.trip.result-worker-enabled:true}")
public class TripAnalysisSchedulingWorker {
    private final AdditionalAttachmentExecutionRepository additions;
    private final TripAnalysisExecutionService analyses;
    private final TripAnalysisExecutionRepository executions;
    private Long afterTripId = 0L;
    private Long afterRemoteTripId = 0L;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}",
            fixedDelayString = "${app.trip.worker-delay-ms:5000}")
    public void startPendingAnalyses() {
        reconcileRemoteTerminations();
        List<Long> candidates = additions.findAnalysisCandidateTripIdsAfter(afterTripId, PageRequest.of(0, 100));
        if (candidates.isEmpty()) {
            afterTripId = 0L;
            return;
        }
        for (Long tripId : candidates) {
            try {
                analyses.startNext(tripId);
            } catch (RuntimeException failure) {
                log.warn("준비된 여행 분석 시작 대기: tripId={}, failure={}",
                        tripId, failure.getClass().getSimpleName());
            }
            afterTripId = tripId;
        }
    }

    private void reconcileRemoteTerminations() {
        List<Long> candidates = executions.findUnconfirmedTripIdsAfter(afterRemoteTripId, PageRequest.of(0, 100));
        if (candidates.isEmpty()) {
            afterRemoteTripId = 0L;
        }
        for (Long tripId : candidates) {
            try {
                analyses.reconcilePending(tripId);
            } catch (RuntimeException failure) {
                log.warn("여행 AI 종료 확인 대기: tripId={}, failure={}", tripId, failure.getClass().getSimpleName());
            }
            afterRemoteTripId = tripId;
        }
    }
}
