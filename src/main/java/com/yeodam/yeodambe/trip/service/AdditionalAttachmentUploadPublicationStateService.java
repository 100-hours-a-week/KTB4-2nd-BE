package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdditionalAttachmentUploadPublicationStateService {

    private final AdditionalAttachmentUploadOutboxRepository outboxRepository;

    public Optional<AdditionalAttachmentUploadOutbox> begin(Long outboxId) {
        var outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow();

        if (outbox.getStatus() == AdditionalAttachmentUploadOutbox.Status.PUBLISHED) {
            return Optional.empty();
        }

        if (outbox.getStatus() == AdditionalAttachmentUploadOutbox.Status.PUBLISHING) {
            throw new TripAttachmentAddNotAllowedException();
        }

        outbox.beginPublishing();
        return Optional.of(outbox);
    }

    public void succeeded(Long outboxId, LocalDateTime publishedAt) {
        var outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow();

        outbox.publicationSucceeded(publishedAt);
    }

    public void failed(Long outboxId, String error) {
        var outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow();

        outbox.publicationFailed(error);
    }
}