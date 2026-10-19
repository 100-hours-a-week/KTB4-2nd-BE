package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MigrationTripStatusController {
    private final MigrationTripResultRepository results;
    private final boolean consumerEnabled;

    public MigrationTripStatusController(
            MigrationTripResultRepository results,
            @Value("${migration.trip.consumer-enabled:false}") boolean consumerEnabled
    ) {
        this.results = results;
        this.consumerEnabled = consumerEnabled;
    }

    @GetMapping("/migration/trips/requests/{requestId}")
    public ResponseEntity<ApiResponse<MigrationTripRequestStatus>> status(
            @PathVariable String requestId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (!consumerEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5").build();
        }
        if (!validRequestId(requestId)) {
            return ResponseEntity.badRequest().build();
        }
        var stored = results.find(requestId);
        if (stored.isEmpty()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(
                    SuccessMessage.TRIP_CREATE_REQUEST_STATUS_FOUND,
                    MigrationTripRequestStatus.pending(requestId)));
        }
        if (!stored.get().userId().equals(Long.valueOf(jwt.getSubject()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ApiResponse<>(
                SuccessMessage.TRIP_CREATE_REQUEST_STATUS_FOUND, stored.get().toStatus()));
    }

    private boolean validRequestId(String requestId) {
        try {
            return UUID.fromString(requestId).toString().equals(requestId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
