package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentUploadOutboxService {

    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SavedMessages save(
            Long tripId,
            Long userId,
            String uploadId,
            AdditionalAttachmentUploadCompletionService.PreparedQueueBatch prepared,
            String requestId
    ) {
        var batch = uploadBatchRepository
                .findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);

        var photosReady = saveIfAbsent(
                batch.getId(),
                prepared.photosReady().type(),
                prepared.photosReady(),
                requestId
        );

        var process = prepared.process().map(message ->
                saveIfAbsent(
                        batch.getId(),
                        message.type(),
                        message,
                        requestId
                )
        );

        return new SavedMessages(photosReady, process);
    }

    private AdditionalAttachmentUploadOutbox saveIfAbsent(
            Long uploadBatchId,
            String messageType,
            Object message,
            String requestId
    ) {
        return outboxRepository
                .findByUploadBatchIdAndMessageType(uploadBatchId, messageType)
                .orElseGet(() -> outboxRepository.saveAndFlush(
                        new AdditionalAttachmentUploadOutbox(
                                uploadBatchId,
                                messageType,
                                objectMapper.writeValueAsString(message),
                                requestId
                        )
                ));
    }

    public record SavedMessages(
            AdditionalAttachmentUploadOutbox photosReady,
            Optional<AdditionalAttachmentUploadOutbox> process
    ) {
    }
}