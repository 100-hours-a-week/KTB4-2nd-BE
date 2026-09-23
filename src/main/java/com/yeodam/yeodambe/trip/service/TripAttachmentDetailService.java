package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAttachmentDetailService {

    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;

    public TripAttachmentDetailResponse findDetail(
            Long userId,
            Long tripAttachmentId
    ) {
        TripAttachment attachment = tripAttachmentRepository
                .findAccessibleById(tripAttachmentId, userId)
                .orElseThrow(AttachmentNotFoundException::new);

        String originalUrl = tripAttachmentStorageClient.createReadUrl(
                attachment.getFile().getObjectKey()
        );

        return new TripAttachmentDetailResponse(
                attachment.getId(),
                originalUrl
        );
    }
}