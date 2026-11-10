package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadOutboxService;
import com.yeodam.yeodambe.trip.repository.TripCreationOutboxRepository;
import com.yeodam.yeodambe.trip.service.TripMutationCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxReceiptService {
    private final InitialAttachmentUploadTransactionService tripCreationService;
    private final AdditionalAttachmentUploadOutboxService additionalAttachmentService;
    private final StoryGenerationPublicationStateService storyPublicationService;
    private final TripMutationCoordinator mutations;
    private final TripCreationOutboxRepository tripCreationOutboxes;

    public void observe(
            String source,
            String id,
            OutboxReceipt receipt
    ) {
        switch (source) {
            case "trip_creation_outbox" -> {
                Long tripId = tripCreationOutboxes.findById(id).orElseThrow().getTripId();
                mutations.run(tripId, () -> tripCreationService.recordPublished(id, receipt));
            }
            case "additional_attachment_upload_outbox" -> additionalAttachmentService.recordPublished(Long.valueOf(id), receipt);
            case "story_generation_outbox" -> storyPublicationService.recordPublished(Long.valueOf(id), receipt);
            default -> throw new IllegalArgumentException(IntegrationInternalErrorMessage.OUTBOX_SOURCE_UNKNOWN.message());
        }
    }
}
