package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.service.TripDraftService;
import com.yeodam.yeodambe.trip.service.request.TripDraftSaveRequest;
import com.yeodam.yeodambe.trip.service.response.TripDraftResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
public class TripDraftController {
    private final TripDraftService drafts;
    private final ObjectMapper mapper;

    @GetMapping("/trip-drafts/me")
    public ResponseEntity<ApiResponse<TripDraftResponse>> find(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.TRIP_DRAFT_FOUND,
                drafts.find(Long.valueOf(jwt.getSubject()))));
    }

    @PutMapping("/trip-drafts/me")
    public ResponseEntity<ApiResponse<TripDraftResponse>> save(@RequestBody JsonNode body,
                                                               @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.TRIP_DRAFT_SAVED,
                drafts.save(Long.valueOf(jwt.getSubject()), TripDraftSaveRequest.fromJson(body, mapper))));
    }

    @DeleteMapping("/trip-drafts/me")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        drafts.delete(Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
