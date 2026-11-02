package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAttachmentDownloadService {

    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final TripAccessService tripAccessService;

    public TripAttachmentDownloadResponse issueDownloadUrl(
            Long userId,
            Long tripAttachmentId
    ) {
        try (WorkflowLog.Stage timing = startDownloadUrlLogging()) {
            TripAttachment attachment = tripAttachmentRepository
                    .findAllActiveWithTripAndFileByIds(List.of(tripAttachmentId)).stream()
                    .findFirst()
                    .orElseThrow(AttachmentNotFoundException::new);
            try {
                tripAccessService.requireReadableTrip(attachment.getTripId(), userId);
            } catch (TripNotFoundException e) {
                throw new AttachmentNotFoundException();
            }

            recordDownloadObject(timing, attachment.getFile().getObjectKey());
            String downloadUrl = tripAttachmentStorageClient.createDownloadUrl(
                    attachment.getFile().getObjectKey(),
                    attachment.getFile().getOriginalFileName()
            );

            logDownloadUrlIssued(timing);
            return new TripAttachmentDownloadResponse(
                    attachment.getId(),
                    downloadUrl
            );
        }
    }

    private WorkflowLog.Stage startDownloadUrlLogging() {
        return WorkflowLog.stage("original_download_url").field("workflow", "attachment_download");
    }

    private void recordDownloadObject(WorkflowLog.Stage timing, String objectKey) {
        timing.field("object_key", objectKey);
    }

    private void logDownloadUrlIssued(WorkflowLog.Stage timing) {
        timing.success();
    }

}