package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripController {
    private final TripService tripService;

    @PostMapping("/trips")
    public ResponseEntity<ApiResponse<TripCreateResponse>> createTrip(
            @Valid @RequestBody TripCreateRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>("UNAUTHORIZED", null));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("TRIP_CREATED", tripService.createTrip(userId, request)));
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
