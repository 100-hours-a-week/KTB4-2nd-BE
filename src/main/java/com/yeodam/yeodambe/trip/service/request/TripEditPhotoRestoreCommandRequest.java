package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record TripEditPhotoRestoreCommandRequest(
        @NotBlank @Size(max = 128) String requestId,
        JsonNode items
) {
}
