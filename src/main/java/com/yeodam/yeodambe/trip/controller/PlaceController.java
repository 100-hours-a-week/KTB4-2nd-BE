package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.service.PlaceService;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidatesResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    @GetMapping
    public ResponseEntity<ApiResponse<PlaceCandidatesResponse>> search(
            @Valid @ModelAttribute PlaceSearchRequest request
    ) {
        return ResponseEntity.ok().body(
                new ApiResponse<>(
                        SuccessMessage.PLACE_CANDIDATES_FOUND,
                        placeService.search(request)
                )
        );
    }
}
