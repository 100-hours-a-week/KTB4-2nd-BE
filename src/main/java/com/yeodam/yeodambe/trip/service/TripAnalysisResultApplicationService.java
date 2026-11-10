package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TripAnalysisResultApplicationService {
    private final AdditionalAttachmentKafkaResultService legacy;
    private final TripAnalysisResultApplicationTransactionService transactions;
    private final TripAnalysisExecutionRepository analyses;
    private final TripEditLock editLock;
    private final TripMutationCoordinator mutations;
    private final AdditionalAttachmentExecutionRepository additions;

    public boolean receive(TripCreateResultEvent event, String raw) {
        Optional<TripAnalysisExecution> candidate = analyses.findById(event.executionId());
        if (candidate.isEmpty()) {
            return additions.findById(event.executionId())
                    .map(addition -> mutations.execute(addition.getTripId(), () -> legacy.receive(event, raw)))
                    .orElse(false);
        }
        return editLock.execute(candidate.get().getTripId(), () -> transactions.receive(event, raw));
    }

    public Optional<TripAnalysisResultClaim> claim() {
        for (TripAnalysisExecution candidate : transactions.findClaimCandidates()) {
            try {
                Optional<TripAnalysisResultClaim> claim = editLock.execute(candidate.getTripId(),
                        () -> transactions.claim(candidate.getExecutionId()));
                if (claim.isPresent()) {
                    return claim;
                }
            } catch (TripEditLockRejectedException failure) {
                rethrowUnlessContended(failure);
            }
        }
        for (AdditionalAttachmentExecution candidate : legacy.findClaimCandidates()) {
            try {
                Optional<TripAnalysisResultClaim> claim = mutations.execute(candidate.getTripId(),
                        () -> legacy.claim(candidate.getExecutionId()));
                if (claim.isPresent()) {
                    return claim;
                }
            } catch (TripEditLockRejectedException failure) {
                rethrowUnlessContended(failure);
            }
        }
        return Optional.empty();
    }

    public boolean renew(TripAnalysisResultClaim claim) {
        if (!analyses.existsById(claim.executionId())) {
            return mutations.execute(claim.tripId(), () -> legacy.renew(claim));
        }
        return editLock.execute(claim.tripId(), () -> transactions.renew(claim));
    }

    public boolean start(TripAnalysisResultClaim claim) {
        return analyses.existsById(claim.executionId()) ? renew(claim)
                : mutations.execute(claim.tripId(), () -> legacy.start(claim));
    }

    public JsonNode validate(TripAnalysisResultClaim claim) {
        return analyses.existsById(claim.executionId()) ? transactions.validate(claim) : legacy.validate(claim);
    }

    public void complete(TripAnalysisResultClaim claim, JsonNode result, Map<String, String> names) {
        if (!analyses.existsById(claim.executionId())) {
            mutations.run(claim.tripId(), () -> legacy.complete(claim, result, names));
            return;
        }
        editLock.execute(claim.tripId(), () -> {
            transactions.complete(claim, result, names);
            return null;
        });
    }

    public void fail(TripAnalysisResultClaim claim, boolean permanent, String reason) {
        if (!analyses.existsById(claim.executionId())) {
            mutations.run(claim.tripId(), () -> legacy.fail(claim, permanent, reason));
            return;
        }
        editLock.execute(claim.tripId(), () -> {
            transactions.fail(claim, reason);
            return null;
        });
    }

    public void expire() {
        for (TripAnalysisExecution candidate : transactions.findExpiredCandidates()) {
            try {
                editLock.execute(candidate.getTripId(), () -> {
                    transactions.expire(candidate.getExecutionId());
                    return null;
                });
            } catch (TripEditLockRejectedException failure) {
                rethrowUnlessContended(failure);
            }
        }
        for (AdditionalAttachmentExecution candidate : legacy.findExpiredCandidates()) {
            try {
                mutations.run(candidate.getTripId(), () -> legacy.expire(candidate.getExecutionId()));
            } catch (TripEditLockRejectedException failure) {
                rethrowUnlessContended(failure);
            }
        }
    }

    private void rethrowUnlessContended(TripEditLockRejectedException failure) {
        if (failure.reason() != TripEditLockRejectedException.Reason.CONTENDED) {
            throw failure;
        }
    }
}
