package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxReceiptService {
    private final InitialAttachmentUploadTransactionService tripCreationService;
    private final AdditionalAttachmentUploadOutboxService additionalAttachmentService;
    private final StoryGenerationPublicationStateService storyPublicationService;

    public void observe(
            String source,
            String id,
            OutboxReceipt receipt
    ) {
        switch (source) {
            case "trip_creation_outbox" -> tripCreationService.recordPublished(id, receipt);
            case "additional_attachment_upload_outbox" -> additionalAttachmentService.recordPublished(Long.valueOf(id), receipt);
            case "story_generation_outbox" -> storyPublicationService.recordPublished(Long.valueOf(id), receipt);
            default -> throw new IllegalArgumentException("Unknown Outbox source");
        }
    }
}
