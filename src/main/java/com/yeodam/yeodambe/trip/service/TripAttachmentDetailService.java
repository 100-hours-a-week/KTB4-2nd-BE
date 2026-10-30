package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAttachmentDetailService {

    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final TripAccessService tripAccessService;

    public TripAttachmentDetailResponse findDetail(
            Long userId,
            Long tripAttachmentId
    ) {
        TripAttachment attachment = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(List.of(tripAttachmentId)).stream()
                .findFirst()
                .orElseThrow(AttachmentNotFoundException::new);
        try {
            tripAccessService.requireReadableTrip(attachment.getTripId(), userId);
        } catch (TripNotFoundException e) {
            throw new AttachmentNotFoundException();
        }

        String displayKey = attachment.getDisplayStorageKey();
        String readKey = displayKey == null || displayKey.isBlank()
                ? attachment.getFile().getObjectKey()
                : displayKey;
        String originalUrl = tripAttachmentStorageClient.createReadUrl(readKey);

        return new TripAttachmentDetailResponse(
                attachment.getId(),
                originalUrl
        );
    }
}
