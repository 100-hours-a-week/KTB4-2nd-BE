package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaimResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate.Cursor;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttachmentConversionService {
    private final AttachmentConversionTransactionService transactions;
    private final TripMutationCoordinator mutations;
    private final AttachmentConversionTaskRepository tasks;
    private final AttachmentConversionOutboxRepository outboxes;

    public List<AttachmentConversionRecoveryCandidate> findRecoveryCandidates(
            Cursor cursor,
            int limit,
            Duration minInterval
    ) {
        return transactions.findRecoveryCandidates(cursor, limit, minInterval);
    }

    public boolean renotifyIfEligible(AttachmentConversionRecoveryCandidate candidate, Duration minInterval) {
        return tasks.findById(candidate.taskId())
                .map(task -> mutations.execute(task.getTripId(),
                        () -> transactions.renotifyIfEligible(candidate, minInterval)))
                .orElse(false);
    }

    public void recordPublished(AttachmentConversionPublication publication) {
        Long tripId = outboxes.findById(publication.outboxId())
                .orElseThrow(InvalidAttachmentConversionMessageException::new).getTripId();
        mutations.run(tripId, () -> transactions.recordPublished(publication));
    }

    public AttachmentConversionClaimResult claim(String taskId, Kind kind, Lane lane) {
        return mutations.execute(tripId(taskId), () -> transactions.claim(taskId, kind, lane));
    }

    public AttachmentConversionAttemptResult beginPhoto(AttachmentConversionClaim claim, Long itemId) {
        return mutations.execute(claim.tripId(), () -> transactions.beginPhoto(claim, itemId));
    }

    public AttachmentConversionStepResult completePhoto(
            AttachmentConversionClaim claim,
            Long itemId,
            DerivedPhotoKeys photo
    ) {
        return mutations.execute(claim.tripId(), () -> transactions.completePhoto(claim, itemId, photo));
    }

    public AttachmentConversionStepResult recordPhotoFailure(
            AttachmentConversionClaim claim,
            Long itemId,
            boolean permanent
    ) {
        return mutations.execute(claim.tripId(), () -> transactions.recordPhotoFailure(claim, itemId, permanent));
    }

    public AttachmentConversionStepResult finishSubBatch(AttachmentConversionClaim claim) {
        return mutations.execute(claim.tripId(), () -> transactions.finishSubBatch(claim));
    }

    public boolean releaseClaim(AttachmentConversionClaim claim) {
        return mutations.execute(claim.tripId(), () -> transactions.releaseClaim(claim));
    }

    public boolean renewLease(AttachmentConversionClaim claim) {
        return mutations.execute(claim.tripId(), () -> transactions.renewLease(claim));
    }

    private Long tripId(String taskId) {
        return tasks.findById(taskId).orElseThrow(InvalidAttachmentConversionMessageException::new).getTripId();
    }
}
