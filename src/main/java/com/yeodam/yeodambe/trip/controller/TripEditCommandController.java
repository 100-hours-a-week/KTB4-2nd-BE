package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.service.TripEditAccessService;
import com.yeodam.yeodambe.trip.service.TripEditCommandService;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoDeleteCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditUpdateCommandRequest;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import com.yeodam.yeodambe.trip.service.response.TripWebSocketAccessResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripEditCommandController {
    private final TripEditCommandService commands;
    private final TripEditAccessService access;

    @PostMapping("/internal/websocket/trips/{tripId}/update")
    public ResponseEntity<ApiResponse<TripEditCommandResultResponse>> update(
            @Positive @PathVariable Long tripId,
            @Valid @RequestBody TripEditUpdateCommandRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commands.update(tripId, Long.valueOf(jwt.getSubject()), request));
    }

    @PostMapping("/internal/websocket/trips/{tripId}/attachments/delete")
    public ResponseEntity<ApiResponse<TripEditCommandResultResponse>> deleteAttachments(
            @Positive @PathVariable Long tripId,
            @Valid @RequestBody TripEditPhotoDeleteCommandRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commands.deleteAttachments(tripId, Long.valueOf(jwt.getSubject()), request));
    }

    @GetMapping("/internal/websocket/trips/{tripId}/access")
    public ResponseEntity<ApiResponse<TripWebSocketAccessResponse>> access(
            @Positive @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(SuccessMessage.TRIP_WEBSOCKET_ACCESS_FOUND,
                        access.findAccess(tripId, Long.valueOf(jwt.getSubject()))));
    }
}
