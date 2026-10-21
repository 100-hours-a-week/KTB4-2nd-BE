package com.yeodam.yeodambe.integration.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RunpodStatusController {
    private final RunpodStatusService service;

    @GetMapping("/ai/runpod/status")
    public ResponseEntity<ApiResponse<RunpodStatusResponse>> getStatus() {
        RunpodStatusResponse response = service.getStatus();
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.RUNPOD_STATUS_FOUND, response));
    }
}
