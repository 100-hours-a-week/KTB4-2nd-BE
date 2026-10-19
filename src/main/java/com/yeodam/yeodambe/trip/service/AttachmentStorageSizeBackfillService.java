package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class AttachmentStorageSizeBackfillService {
    private final TripAttachmentRepository attachments;
    private final TripAttachmentStorageClient storage;
    private final TripRepository trips;
    private final UserStatsService userStats;
    private final TransactionTemplate transaction;

    public AttachmentStorageSizeBackfillService(
            TripAttachmentRepository attachments,
            TripAttachmentStorageClient storage,
            TripRepository trips,
            UserStatsService userStats,
            PlatformTransactionManager transactionManager
    ) {
        this.attachments = attachments;
        this.storage = storage;
        this.trips = trips;
        this.userStats = userStats;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void backfillOne(Long attachmentId) {
        Snapshot snapshot = transaction.execute(status -> {
            TripAttachment attachment = attachments.findById(attachmentId).orElse(null);
            if (!isEligible(attachment)) {
                return null;
            }
            StoredFile file = attachment.getFile();
            if (file.getOriginalSizeBytes() != null
                    && attachment.getAnalyzeSizeBytes() != null
                    && attachment.getPreviewSizeBytes() != null
                    && (attachment.getDisplayStorageKey() == null
                        || attachment.getDisplaySizeBytes() != null)) {
                return null;
            }
            return new Snapshot(attachment.getTripId(), attachment.getTrip().getUserId(),
                    file.getObjectKey(), attachment.getAnalyzeStorageKey(),
                    attachment.getPreviewStorageKey(), attachment.getDisplayStorageKey(),
                    file.getOriginalSizeBytes(), attachment.getAnalyzeSizeBytes(),
                    attachment.getPreviewSizeBytes(), attachment.getDisplaySizeBytes());
        });
        if (snapshot == null) {
            return;
        }

        long originalSize = resolveSize(snapshot.originalKey(), snapshot.originalSize());
        long analyzeSize = resolveSize(snapshot.analyzeKey(), snapshot.analyzeSize());
        long previewSize = resolveSize(snapshot.previewKey(), snapshot.previewSize());
        Long displaySize = snapshot.displayKey() == null ? null
                : resolveSize(snapshot.displayKey(), snapshot.displaySize());

        transaction.executeWithoutResult(status -> {
            if (trips.findOwnedActiveForUpdate(snapshot.tripId(), snapshot.userId()).isEmpty()) {
                return;
            }
            TripAttachment attachment = attachments.findById(attachmentId).orElse(null);
            if (!isEligible(attachment)) {
                return;
            }
            StoredFile file = attachment.getFile();
            if (!Objects.equals(file.getObjectKey(), snapshot.originalKey())
                    || !Objects.equals(attachment.getAnalyzeStorageKey(), snapshot.analyzeKey())
                    || !Objects.equals(attachment.getPreviewStorageKey(), snapshot.previewKey())
                    || !Objects.equals(attachment.getDisplayStorageKey(), snapshot.displayKey())) {
                return;
            }
            file.storageSize(keepExisting(file.getOriginalSizeBytes(), originalSize));
            attachment.storageSizes(
                    keepExisting(attachment.getAnalyzeSizeBytes(), analyzeSize),
                    keepExisting(attachment.getPreviewSizeBytes(), previewSize),
                    keepExisting(attachment.getDisplaySizeBytes(), displaySize));
        });
    }

    public void refreshUserStats(Long userId) {
        transaction.executeWithoutResult(status -> userStats.refreshFromActiveTrips(userId));
    }

    private boolean isEligible(TripAttachment attachment) {
        return attachment != null && attachment.getDeletedAt() == null
                && attachment.getFile().getDeletedAt() == null
                && attachment.getTrip().getDeletedAt() == null
                && attachment.getTrip().getProcessingStatus() == ProcessingStatus.COMPLETED;
    }

    private long resolveSize(String objectKey, Long existingSize) {
        if (existingSize != null) {
            return existingSize;
        }
        return storage.size(objectKey);
    }

    private Long keepExisting(Long existing, Long measured) {
        return existing == null ? measured : existing;
    }

    private record Snapshot(Long tripId, Long userId,
                            String originalKey, String analyzeKey, String previewKey, String displayKey,
                            Long originalSize, Long analyzeSize, Long previewSize, Long displaySize) { }
}
