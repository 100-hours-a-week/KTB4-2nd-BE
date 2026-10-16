package com.yeodam.yeodambe.migrationprobe.service.response;

public record MigrationProbeResponse(
        String runId,
        String requestId,
        String result
) {
}
