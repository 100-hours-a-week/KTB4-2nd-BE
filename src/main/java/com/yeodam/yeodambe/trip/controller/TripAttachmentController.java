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
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadUrlResponse;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadCompletionService;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.service.UnclassifiedFolderListService;
import com.yeodam.yeodambe.trip.service.response.UnclassifiedFolderListResponse;

import java.util.List;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class TripAttachmentController {
    private final TripAttachmentService service;
    private final TripAttachmentListService tripAttachmentListService;
    private final TripAttachmentDetailService tripAttachmentDetailService;
    private final TripAttachmentDeletionService tripAttachmentDeletionService;
    private final TripAttachmentDownloadService tripAttachmentDownloadService;
    private final BulkAttachmentDownloadService bulkAttachmentDownloadService;
    private final InitialAttachmentUploadUrlService uploadUrlService;
    private final InitialAttachmentUploadCompletionService uploadCompletion;
    private final UnclassifiedFolderListService unclassifiedFolderListService;

    @GetMapping("/trips/{tripId}/unclassified-folders")
    public ResponseEntity<ApiResponse<UnclassifiedFolderListResponse>> findUnclassifiedFolders(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        UnclassifiedFolderListResponse result =
                unclassifiedFolderListService.findFolders(userId, tripId);
        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.UNCLASSIFIED_FOLDER_LIST_FOUND, result)
        );
    }

    @PostMapping(value = "/trips/{tripId}/initial-attachments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TripProcessingStatusResponse>> completeInitialAttachmentUpload(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody InitialAttachmentUploadCompleteRequest request
    ) {
        if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
            throw new InvalidAttachmentUploadException();
        }
        Optional<TripProcessingStatusResponse> result = uploadCompletion.complete(
                tripId, Long.valueOf(jwt.getSubject()), request);
        if (result.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.TRIP_PROCESSING_STATUS_FOUND, result.get()));
    }

    @PostMapping(
            value = "/trips/{tripId}/initial-attachments/upload-urls",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<InitialAttachmentUploadUrlResponse>>
    issueInitialAttachmentUploadUrls(
            @PathVariable Long tripId,
            @RequestBody InitialAttachmentUploadUrlRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        InitialAttachmentUploadUrlResponse data =
                uploadUrlService.issueUploadUrls(tripId, userId, request);

        return ResponseEntity.ok(
                new ApiResponse<>(
                        SuccessMessage.TRIP_INITIAL_ATTACHMENT_UPLOAD_URLS_ISSUED,
                        data
                )
        );
    }

    @PostMapping(value = "/trips/{tripId}/initial-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<TripProcessingStatusResponse>> uploadInitialAttachments(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "attachments[]", required = false) List<MultipartFile> files,
            @RequestParam(required = false) Integer batchNo,
            @RequestParam(required = false) Integer totalAttachmentCount,
            @RequestParam(required = false) Boolean complete
    ) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(file -> file == null || file.isEmpty())
                || batchNo == null || batchNo < 1
                || totalAttachmentCount == null || totalAttachmentCount < 1 || totalAttachmentCount > 200
                || complete == null) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_UPLOAD, null));
        }
        if (files.size() > 10) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(new ApiResponse<>(ErrorMessage.ATTACHMENT_UPLOAD_LIMIT_EXCEEDED, null));
        }

        Optional<TripProcessingStatusResponse> result = service.uploadInitialAttachments(
                tripId, Long.valueOf(jwt.getSubject()), files, batchNo, totalAttachmentCount, complete);
        return result
                .map(status -> ResponseEntity.ok(
                        new ApiResponse<>(SuccessMessage.TRIP_PROCESSING_STATUS_FOUND, status)))
                .orElseGet(() -> ResponseEntity.noContent().build());
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
