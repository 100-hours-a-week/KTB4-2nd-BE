package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.service.TripAttachmentService;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.yeodam.yeodambe.trip.service.TripAttachmentListService;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import com.yeodam.yeodambe.trip.service.TripAttachmentDetailService;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import com.yeodam.yeodambe.trip.service.TripAttachmentDeletionService;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentDeleteRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.yeodam.yeodambe.trip.service.TripAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentDownloadRequest;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentDownloadResponse;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TripAttachmentController {
    private final TripAttachmentService service;
    private final TripAttachmentListService tripAttachmentListService;
    private final TripAttachmentDetailService tripAttachmentDetailService;
    private final TripAttachmentDeletionService tripAttachmentDeletionService;
    private final TripAttachmentDownloadService tripAttachmentDownloadService;
    private final BulkAttachmentDownloadService bulkAttachmentDownloadService;

    @PostMapping(value = "/trips/{tripId}/initial-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<TripProcessingStatusResponse>> uploadInitialAttachments(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "attachments[]", required = false) List<MultipartFile> files
    ) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(file -> file == null || file.isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_UPLOAD, null));
        }
        if (files.size() > 200) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(new ApiResponse<>(ErrorMessage.ATTACHMENT_UPLOAD_LIMIT_EXCEEDED, null));
        }

        TripProcessingStatusResponse result = service.uploadInitialAttachments(
                tripId, Long.valueOf(jwt.getSubject()), files);
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.TRIP_PROCESSING_STATUS_FOUND, result));
    }

    @GetMapping("/trips/{tripId}/place-folders/{tripPlaceId}/attachments")
    public ResponseEntity<ApiResponse<TripAttachmentListResponse>> findAttachmentsByPlaceFolder(
            @PathVariable Long tripId,
            @PathVariable Long tripPlaceId,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        TripAttachmentListResponse result =
                tripAttachmentListService.findByPlaceFolder(
                        userId,
                        tripId,
                        tripPlaceId,
                        cursor
                );

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.ATTACHMENT_LIST_FOUND, result)
        );
    }

    @GetMapping("/attachments/{tripAttachmentId}")
    public ResponseEntity<ApiResponse<TripAttachmentDetailResponse>> findAttachmentDetail(
            @PathVariable Long tripAttachmentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        TripAttachmentDetailResponse result =
                tripAttachmentDetailService.findDetail(
                        userId,
                        tripAttachmentId
                );

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.ATTACHMENT_FOUND, result)
        );
    }

    @GetMapping("/attachments/{tripAttachmentId}/download")
    public ResponseEntity<ApiResponse<TripAttachmentDownloadResponse>> issueDownloadUrl(
            @PathVariable Long tripAttachmentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        TripAttachmentDownloadResponse result =
                tripAttachmentDownloadService.issueDownloadUrl(
                        userId,
                        tripAttachmentId
                );

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.ATTACHMENT_DOWNLOAD_URL_ISSUED, result)
        );
    }

    @DeleteMapping("/attachments/{tripAttachmentId}")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable Long tripAttachmentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        tripAttachmentDeletionService.deleteOne(userId, tripAttachmentId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/attachments/bulk-delete")
    public ResponseEntity<Void> deleteAttachments(
            @RequestBody(required = false) BulkAttachmentDeleteRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        tripAttachmentDeletionService.deleteBulk(
                userId,
                request == null ? null : request.tripAttachmentIds()
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/attachments/bulk-download")
    public ResponseEntity<ApiResponse<BulkAttachmentDownloadResponse>> issueBulkDownloadUrl(
            @RequestBody(required = false) BulkAttachmentDownloadRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        BulkAttachmentDownloadResponse result =
                bulkAttachmentDownloadService.issueDownloadUrl(
                        userId,
                        request == null ? null : request.tripAttachmentIds()
                );

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.BULK_ATTACHMENT_DOWNLOAD_URL_ISSUED, result)
        );
    }
}
