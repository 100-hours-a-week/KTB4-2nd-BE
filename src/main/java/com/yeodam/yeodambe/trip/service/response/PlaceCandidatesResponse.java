package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record PlaceCandidatesResponse(
        List<PlaceCandidateResponse> items
) {
}
