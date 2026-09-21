package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripController {
    private final TripService tripService;
    private final TripProcessingStatusService processingStatusService;

    @PostMapping("/trips")
    public ResponseEntity<ApiResponse<TripCreateResponse>> createTrip(
            @Valid @RequestBody TripCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("TRIP_CREATED", tripService.createTrip(Long.valueOf(jwt.getSubject()), request)));
    }

    @GetMapping("/trips/{tripId}/processing-status")
    public ResponseEntity<ApiResponse<TripProcessingStatusResponse>> getProcessingStatus(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(new ApiResponse<>(
                "TRIP_PROCESSING_STATUS_FOUND",
                processingStatusService.findStatus(tripId, Long.valueOf(jwt.getSubject()))
        ));
    }

    @GetMapping("/trips/map")
    public ResponseEntity<ApiResponse<TripMapResponse>> findMap(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        TripMapResponse data = tripService.findMap(userId);

        return ResponseEntity.ok(
                new ApiResponse<>("TRIP_MAP_FOUND", data)
        );
    }
}
