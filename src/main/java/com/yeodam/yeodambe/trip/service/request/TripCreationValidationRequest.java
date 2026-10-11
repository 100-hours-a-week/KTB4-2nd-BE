package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TripCreationValidationRequest(
        @NotNull @Size(min = 1, max = 200)
        List<@NotNull @Valid TripAttachmentMetadataRequest> attachmentMetadata
) {
}
