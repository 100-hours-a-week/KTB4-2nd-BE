package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentAnalysisResultTransactionService {
    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentAnalysisPreparationService preparationService;
    private final TripAnalysisResultService tripAnalysisResultService;

    @Transactional
    public void saveResult(Long tripId, Long userId, String additionId, JsonNode result, Map<String, String> names) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) throw new TripAttachmentAddNotAllowedException();
        List<AdditionalAttachmentUploadBatch> batches = uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(additionId);
        if (batches.isEmpty() || batches.stream().anyMatch(batch -> !batch.getTripId().equals(tripId)
                || !batch.getUserId().equals(userId))) throw new InvalidAttachmentUploadException();
        if (batches.stream().allMatch(batch -> batch.getStatus() == AdditionalAttachmentUploadStatus.COMPLETED
                || batch.getStatus() == AdditionalAttachmentUploadStatus.FAILED)) return;
        if (!preparationService.isPrepared(tripId, userId, additionId)) throw new TripAttachmentAddNotAllowedException();
        validateIdentity(tripId, additionId, result);
        tripAnalysisResultService.replaceCompleted(tripId, userId, result, names);
        batches.forEach(AdditionalAttachmentUploadBatch::completeAddition);
    }

    @Transactional
    public void fail(Long tripId, Long userId, String additionId) {
        tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (!preparationService.isPrepared(tripId, userId, additionId)) return;
        uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(additionId)
                .forEach(AdditionalAttachmentUploadBatch::failAddition);
    }

    private void validateIdentity(Long tripId, String additionId, JsonNode result) {
        if (result == null || result.path("trip_id").asLong(-1) != tripId
                || !additionId.equals(result.path("execution_id").asString())) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }
    }
}
