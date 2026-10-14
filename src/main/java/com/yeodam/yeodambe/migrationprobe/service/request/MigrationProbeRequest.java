package com.yeodam.yeodambe.migrationprobe.service.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = MigrationProbeRequestDeserializer.class)
public record MigrationProbeRequest(
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String runId,
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String requestId,
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}")
        String payloadHash
) {
}
