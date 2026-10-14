package com.yeodam.yeodambe.migrationprobe.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.migrationprobe.exception.InvalidMigrationProbeRequestException;
import com.yeodam.yeodambe.migrationprobe.service.MigrationProbeService;
import com.yeodam.yeodambe.migrationprobe.service.request.MigrationProbeRequest;
import com.yeodam.yeodambe.migrationprobe.service.response.MigrationProbeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@Profile("staging | prod")
@RequiredArgsConstructor
public class MigrationProbeController {

    private final MigrationProbeService service;

    @PostMapping(value = "/internal/migration-write-probe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MigrationProbeResponse>> write(
            @Valid @RequestBody MigrationProbeRequest body,
            HttpServletRequest request
    ) {
        validateRequestId(request, body.requestId());
        MigrationProbeResponse result = service.write(body.runId(), body.requestId(), body.payloadHash());
        boolean created = result.result().equals("created");
        HttpStatus status = created ? HttpStatus.CREATED : HttpStatus.OK;
        SuccessMessage message = created
                ? SuccessMessage.MIGRATION_WRITE_PROBE_CREATED
                : SuccessMessage.MIGRATION_WRITE_PROBE_ALREADY_EXISTS;

        return ResponseEntity.status(status).body(new ApiResponse<>(message, result));
    }

    private void validateRequestId(HttpServletRequest request, String requestId) {
        var headers = Collections.list(request.getHeaders("X-Request-ID"));
        if (headers.size() != 1 || !requestId.equals(headers.getFirst())) {
            throw new InvalidMigrationProbeRequestException();
        }
    }
}
