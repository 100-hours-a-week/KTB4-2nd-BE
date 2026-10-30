package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.service.TripWebSocketAccessService;
import com.yeodam.yeodambe.trip.service.response.TripPresenceAccessResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripWebSocketAccessController {
    private final TripWebSocketAccessService service;

    @GetMapping("/internal/websocket/trips/{tripId}/presence-access")
    public ResponseEntity<ApiResponse<TripPresenceAccessResponse>> findPresenceAccess(
            @PathVariable @Positive Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        TripPresenceAccessResponse data = service.findPresenceAccess(
                tripId, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(SuccessMessage.TRIP_PRESENCE_ACCESS_FOUND, data));
    }
}
