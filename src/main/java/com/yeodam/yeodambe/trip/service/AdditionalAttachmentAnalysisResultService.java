package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentAnalysisResultService {
    private final AdditionalAttachmentAnalysisPreparationService preparationService;
    private final TripPlaceNameService tripPlaceNameService;
    private final AdditionalAttachmentAnalysisResultTransactionService resultTransactionService;

    public void acceptResult(Long tripId, Long userId, String additionId, JsonNode result) {
        if (!preparationService.isPrepared(tripId, userId, additionId)) return;
        if (result == null || result.path("trip_id").asLong(-1) != tripId
                || !additionId.equals(result.path("execution_id").asString())) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }
        var names = tripPlaceNameService.resolve(tripId, additionId, result,
                () -> preparationService.isPrepared(tripId, userId, additionId));
        resultTransactionService.saveResult(tripId, userId, additionId, result, names);
    }

    public void fail(Long tripId, Long userId, String additionId) {
        resultTransactionService.fail(tripId, userId, additionId);
    }
}
