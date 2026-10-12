package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkAttachmentRestoreRequest(
        @NotEmpty @Size(max = 200) List<@NotNull @Valid Item> items
) {
    public record Item(
            @NotNull @Positive Long tripAttachmentId,
            @NotNull @Positive Long tripPlaceId
    ) {
    }
}
