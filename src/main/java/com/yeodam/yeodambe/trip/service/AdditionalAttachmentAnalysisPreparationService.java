package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.integration.service.request.PhotosReadyMessage;
import com.yeodam.yeodambe.integration.service.request.PhotoProcessMessage;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentAnalysisPreparationService {
    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadItemRepository uploadItemRepository;
    private final InitialAttachmentUploadItemRepository initialUploadItemRepository;

    @Transactional
    public PreparedAnalysis prepare(Long tripId, Long userId, String uploadId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) throw new TripAttachmentAddNotAllowedException();
        AdditionalAttachmentUploadBatch last = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (!last.getLastBatch()) throw new InvalidAttachmentUploadException();
        List<AdditionalAttachmentUploadBatch> batches = uploadBatchRepository
                .findAllByAdditionIdOrderByBatchNoAsc(last.getAdditionId());
        if (batches.size() != last.getBatchNo()) throw new InvalidAttachmentUploadException();
        for (int index = 0; index < batches.size(); index++) {
            AdditionalAttachmentUploadBatch batch = batches.get(index);
            if (!batch.getTripId().equals(tripId) || !batch.getUserId().equals(userId)
                    || batch.getBatchNo() != index + 1
                    || !batch.getTotalAttachmentCount().equals(last.getTotalAttachmentCount())
                    || batch.getLastBatch() != (index == batches.size() - 1)
                    || (batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                    && batch.getStatus() != AdditionalAttachmentUploadStatus.PREPARED)) {
                throw new TripAttachmentAddNotAllowedException();
            }
        }
        List<AdditionalAttachmentUploadItem> addedItems = uploadItemRepository.findAdditionItems(last.getAdditionId());
        if (addedItems.size() != last.getTotalAttachmentCount()
                || addedItems.stream().anyMatch(item -> item.getTripAttachmentId() == null)) {
            throw new InvalidAttachmentUploadException();
        }
        List<TripAttachment> photos = tripAttachmentRepository.findAllForReanalysis(tripId);
        Set<Long> photoIds = new HashSet<>(photos.stream().map(TripAttachment::getId).toList());
        if (photos.isEmpty() || photos.size() > 200 || addedItems.stream()
                .anyMatch(item -> !photoIds.contains(item.getTripAttachmentId()))) {
            throw new TripAttachmentAddNotAllowedException();
        }
        Map<Long, OffsetDateTime> takenAt = new HashMap<>();
        for (InitialAttachmentUploadItem item : initialUploadItemRepository.findAllByTripAttachmentIdIn(photoIds)) {
            if (item.getTakenAtWithOffset() != null) takenAt.put(item.getTripAttachmentId(), OffsetDateTime.parse(item.getTakenAtWithOffset()));
        }
        for (AdditionalAttachmentUploadItem item : uploadItemRepository.findAllByTripAttachmentIdIn(photoIds)) {
            if (item.getTakenAtWithOffset() != null) takenAt.put(item.getTripAttachmentId(), OffsetDateTime.parse(item.getTakenAtWithOffset()));
        }
        List<TripPhotoAnalysisRequest.Region> regions = tripRegionRepository
                .findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(tripId).stream()
                .map(region -> new TripPhotoAnalysisRequest.Region(region.getLatitude(), region.getLongitude())).toList();
        if (regions.isEmpty()) throw new IllegalStateException(TripInternalErrorMessage.TRIP_REGION_MISSING.message());
        List<TripPhotoAnalysisRequest.Photo> inputs = photos.stream().map(photo ->
                new TripPhotoAnalysisRequest.Photo(photo.getId(), photo.getAnalyzeStorageKey(),
                        takenAt.get(photo.getId()),
                        photo.getRegionOrigin() == RegionOrigin.EXIF ? photo.getLatitude() : null,
                        photo.getRegionOrigin() == RegionOrigin.EXIF ? photo.getLongitude() : null,
                        photo.getDeviceModel())).toList();
        batches.forEach(AdditionalAttachmentUploadBatch::markPrepared);
        return new PreparedAnalysis(tripId, last.getAdditionId(), new TripPhotoAnalysisRequest(
                last.getAdditionId(), trip.getTripName(),
                new TripPhotoAnalysisRequest.Period(trip.getStartDate(), trip.getEndDate()), regions, inputs));
    }

    @Transactional(readOnly = true)
    public boolean isPrepared(Long tripId, Long userId, String additionId) {
        List<AdditionalAttachmentUploadBatch> batches = uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(additionId);
        return !batches.isEmpty() && batches.get(batches.size() - 1).getLastBatch()
                && batches.stream().allMatch(batch -> batch.getTripId().equals(tripId)
                && batch.getUserId().equals(userId) && batch.getStatus() == AdditionalAttachmentUploadStatus.PREPARED)
                && tripRepository.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId).isPresent();
    }

    @Transactional
    public PhotosReadyMessage photosReady(Long tripId, Long userId, String uploadId, String executionId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) throw new TripAttachmentAddNotAllowedException();
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                && batch.getStatus() != AdditionalAttachmentUploadStatus.PREPARED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        List<AdditionalAttachmentUploadItem> items = uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());
        if (items.isEmpty() || items.stream().anyMatch(item -> item.getTripAttachmentId() == null)) {
            throw new InvalidAttachmentUploadException();
        }
        List<PhotosReadyMessage.Attachment> ready = new ArrayList<>();
        for (AdditionalAttachmentUploadItem item : items) {
            TripAttachment photo = tripAttachmentRepository.findById(item.getTripAttachmentId())
                    .orElseThrow(TripAttachmentAddNotAllowedException::new);
            if (!photo.getTripId().equals(tripId) || photo.getDeletedAt() != null
                    || photo.getFile().getDeletedAt() != null) throw new TripAttachmentAddNotAllowedException();
            ready.add(new PhotosReadyMessage.Attachment(photo.getId(), photo.getAnalyzeStorageKey()));
        }
        return PhotosReadyMessage.create(tripId, executionId, batch.getBatchNo(), ready);
    }

    public record PreparedAnalysis(Long tripId, String additionId, TripPhotoAnalysisRequest request) {
        public PhotoProcessMessage processMessage(String executionId) {
            return PhotoProcessMessage.from(tripId, executionId, request);
        }
    }
}
