package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InitialUploadExecutionRegistry {
    private static final long MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024;

    private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

    public Reservation reserveBatch(Long tripId, int batchNo, int totalAttachmentCount) {
        if (batchNo < 1 || totalAttachmentCount < 1 || totalAttachmentCount > 200) {
            throw new InvalidAttachmentUploadException();
        }

        if (batchNo == 1) {
            String executionId = UUID.randomUUID().toString();
            Execution created = new Execution(
                    executionId, State.STORING, 1, 1, totalAttachmentCount, 0, 0, List.of());
            if (executions.putIfAbsent(tripId, created) != null) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }
            return new Reservation(executionId, true);
        }

        Execution reserved = executions.compute(tripId, (ignored, current) -> {
            requireReservable(current, batchNo, totalAttachmentCount);
            return new Execution(
                    current.id(), State.STORING, current.nextBatchNo(), batchNo,
                    current.totalAttachmentCount(), current.attachmentCount(),
                    current.uploadedBytes(), current.photos());
        });
        return new Reservation(reserved.id(), false);
    }

    public Snapshot completeBatch(
            Long tripId,
            String executionId,
            int batchNo,
            long batchBytes,
            List<StoredPhoto> batchPhotos,
            boolean complete
    ) {
        if (batchBytes < 0 || batchPhotos == null || batchPhotos.isEmpty()) {
            throw new InvalidAttachmentUploadException();
        }

        Execution updated = executions.compute(tripId, (ignored, current) -> {
            requireStoring(current, executionId, batchNo);

            int attachmentCount = Math.addExact(current.attachmentCount(), batchPhotos.size());
            long uploadedBytes = Math.addExact(current.uploadedBytes(), batchBytes);
            if (uploadedBytes > MAX_TOTAL_BYTES) throw new AttachmentUploadLimitExceededException();
            if (attachmentCount > current.totalAttachmentCount()
                    || complete != (attachmentCount == current.totalAttachmentCount())) {
                throw new InvalidAttachmentUploadException();
            }

            List<StoredPhoto> photos = new ArrayList<>(current.photos());
            photos.addAll(batchPhotos);
            return new Execution(
                    current.id(), complete ? State.STORING : State.UPLOADING,
                    batchNo + 1, 0, current.totalAttachmentCount(), attachmentCount,
                    uploadedBytes, List.copyOf(photos));
        });
        return Snapshot.from(updated);
    }

    public void failBatch(Long tripId, String executionId, int batchNo) {
        executions.computeIfPresent(tripId, (ignored, current) -> {
            if (!matchesStoring(current, executionId, batchNo)) return current;
            if (batchNo == 1 && current.attachmentCount() == 0) return null;
            return new Execution(
                    current.id(), State.UPLOADING, batchNo, 0,
                    current.totalAttachmentCount(), current.attachmentCount(),
                    current.uploadedBytes(), current.photos());
        });
    }

    public Snapshot snapshot(Long tripId) {
        Execution execution = executions.get(tripId);
        if (execution == null) throw new TripInitialAttachmentUploadNotAllowedException();
        return Snapshot.from(execution);
    }

    public String reserve(Long tripId) {
        return reserveBatch(tripId, 1, 200).executionId();
    }

    public boolean isCurrent(Long tripId, String executionId) {
        Execution execution = executions.get(tripId);
        return execution != null
                && execution.state() != State.CANCELED
                && execution.id().equals(executionId);
    }

    public boolean markAnalysisStarted(Long tripId, String executionId) {
        AtomicBoolean started = new AtomicBoolean();
        executions.computeIfPresent(tripId, (ignored, current) -> {
            if (!current.id().equals(executionId) || current.state() == State.CANCELED) return current;
            started.set(true);
            return new Execution(
                    current.id(), State.ANALYZING, current.nextBatchNo(), 0,
                    current.totalAttachmentCount(), current.attachmentCount(),
                    current.uploadedBytes(), current.photos());
        });
        return started.get();
    }

    public boolean isAnalysisStarted(Long tripId) {
        Execution execution = executions.get(tripId);
        return execution != null && execution.state() == State.ANALYZING;
    }

    public boolean cancel(Long tripId) {
        AtomicBoolean analysisStarted = new AtomicBoolean();
        executions.computeIfPresent(tripId, (ignored, current) -> {
            analysisStarted.set(current.state() == State.ANALYZING);
            return new Execution(
                    current.id(), State.CANCELED, current.nextBatchNo(), 0,
                    current.totalAttachmentCount(), current.attachmentCount(),
                    current.uploadedBytes(), current.photos());
        });
        return analysisStarted.get();
    }

    public void release(Long tripId, String executionId) {
        executions.computeIfPresent(tripId, (ignored, current) ->
                current.id().equals(executionId) ? null : current);
    }

    private void requireReservable(Execution current, int batchNo, int totalAttachmentCount) {
        if (current == null
                || current.state() != State.UPLOADING
                || current.nextBatchNo() != batchNo
                || current.totalAttachmentCount() != totalAttachmentCount) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    private void requireStoring(Execution current, String executionId, int batchNo) {
        if (!matchesStoring(current, executionId, batchNo)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    private boolean matchesStoring(Execution current, String executionId, int batchNo) {
        return current != null
                && current.state() == State.STORING
                && current.id().equals(executionId)
                && current.activeBatchNo() == batchNo;
    }

    public record Reservation(String executionId, boolean firstBatch) {
    }

    public record StoredPhoto(
            StoredFile original,
            TripAttachment attachment,
            DerivedPhotoKeys metadata
    ) {
    }

    public record Snapshot(
            String executionId,
            State state,
            int nextBatchNo,
            int activeBatchNo,
            int totalAttachmentCount,
            int attachmentCount,
            long uploadedBytes,
            List<StoredPhoto> photos
    ) {
        private static Snapshot from(Execution execution) {
            return new Snapshot(
                    execution.id(), execution.state(), execution.nextBatchNo(), execution.activeBatchNo(),
                    execution.totalAttachmentCount(), execution.attachmentCount(),
                    execution.uploadedBytes(), execution.photos());
        }
    }

    public enum State {
        UPLOADING,
        STORING,
        ANALYZING,
        CANCELED
    }

    private record Execution(
            String id,
            State state,
            int nextBatchNo,
            int activeBatchNo,
            int totalAttachmentCount,
            int attachmentCount,
            long uploadedBytes,
            List<StoredPhoto> photos
    ) {
    }
}
