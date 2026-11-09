package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripAnalysisExecutionTransactionService.RemoteTermination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripAnalysisExecutionService {
    private final TripEditLock editLock;
    private final TripAnalysisExecutionTransactionService transactions;
    private final TripPhotoAnalysisService remote;
    private final TripRepository trips;

    public Optional<String> startNext(Long tripId) {
        if (!reconcilePending(tripId)) {
            return Optional.empty();
        }
        return editLock.execute(tripId, () -> transactions.startNext(tripId));
    }

    public boolean reconcilePending(Long tripId) {
        List<RemoteTermination> pending = transactions.findPendingRemoteTerminations(tripId);
        if (!pending.isEmpty() && trips.findById(tripId).filter(trip -> trip.getDeletedAt() != null).isPresent()) {
            try {
                remote.cancel(tripId);
            } catch (RuntimeException failure) {
                log.info("삭제된 여행의 AI 취소 전달 대기: tripId={}, failure={}", tripId, failure.getClass().getSimpleName());
            }
        }
        boolean confirmed = true;
        for (RemoteTermination predecessor : pending) {
            if (!isRemoteTerminal(tripId, predecessor.executionId())) {
                confirmed = false;
                continue;
            }
            editLock.execute(tripId, () -> {
                transactions.confirmRemoteTermination(tripId, predecessor);
                return null;
            });
        }
        return confirmed;
    }

    private boolean isRemoteTerminal(Long tripId, String executionId) {
        try {
            TripPhotoAnalysisStatusResponse status = remote.findStatus(tripId, executionId);
            return switch (status.status()) {
                case COMPLETED, FAILED, CANCELED -> true;
                case QUEUED, PROCESSING -> false;
            };
        } catch (RuntimeException failure) {
            log.info("이전 AI 실행의 종료 확인 대기: tripId={}, executionId={}, failure={}",
                    tripId, executionId, failure.getClass().getSimpleName());
            return false;
        }
    }
}
