package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
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

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TripAttachmentController {
    private final TripAttachmentService service;
    private final TripAttachmentListService tripAttachmentListService;

    @PostMapping(value = "/trips/{tripId}/initial-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<TripProcessingStatusResponse>> uploadInitialAttachments(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "attachments[]", required = false) List<MultipartFile> files
    ) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(file -> file == null || file.isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>("INVALID_ATTACHMENT_UPLOAD", null));
        }
        if (files.size() > 200) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(new ApiResponse<>("ATTACHMENT_UPLOAD_LIMIT_EXCEEDED", null));
        }

        TripProcessingStatusResponse result = service.uploadInitialAttachments(
                tripId, Long.valueOf(jwt.getSubject()), files);
        return ResponseEntity.ok(new ApiResponse<>("TRIP_PROCESSING_STATUS_FOUND", result));
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
                new ApiResponse<>("ATTACHMENT_LIST_FOUND", result)
        );
    }
}
