package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AttachmentRestoreRequest(
        @NotNull @Positive Long tripPlaceId
) {
}
