package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.NotBlank;

public record InitialAttachmentUploadCompleteRequest(
        @NotBlank String uploadId
) {
}
