package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.TripProcessingCancellationService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripFavoriteResponse;
import com.yeodam.yeodambe.trip.service.response.TripListResponse;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripController {
    private final TripService tripService;
    private final TripProcessingStatusService processingStatusService;
    private final TripProcessingCancellationService processingCancellationService;

    @PostMapping("/trips")
    public ResponseEntity<ApiResponse<TripCreateResponse>> createTrip(
            @Valid @RequestBody TripCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("TRIP_CREATED", tripService.createTrip(Long.valueOf(jwt.getSubject()), request)));
    }

    @PostMapping("/trips/{tripId}/favorite")
    public ResponseEntity<ApiResponse<TripFavoriteResponse>> registerFavorite(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(new ApiResponse<>(
                "FAVORITE_REGISTERED",
                tripService.registerFavorite(tripId, Long.valueOf(jwt.getSubject()))
        ));
    }

    @DeleteMapping("/trips/{tripId}/favorite")
    public ResponseEntity<Void> removeFavorite(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        tripService.removeFavorite(tripId, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
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

    @DeleteMapping("/trips/{tripId}/processing")
    public ResponseEntity<Void> cancelProcessing(
            @PathVariable Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        processingCancellationService.cancel(tripId, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
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

    @GetMapping("/trips")
    public ResponseEntity<ApiResponse<TripListResponse>> findTrips(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String favorite,
            @AuthenticationPrincipal Jwt jwt
    ) {
        TripListRequest request = TripListRequest.from(cursor, sort, favorite);
        return ResponseEntity.ok(new ApiResponse<>(
                "TRIP_LIST_FOUND",
                tripService.findTrips(Long.valueOf(jwt.getSubject()), request)
        ));
    }
}
