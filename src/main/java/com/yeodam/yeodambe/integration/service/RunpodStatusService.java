package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.integration.client.RunpodStatusClient;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunpodStatusService {
    private final RunpodStatusClient client;

    public RunpodStatusResponse getStatus() {
        RunpodStatusResponse response = client.getStatus();
        if (response == null) {
            log.atWarn()
                    .addKeyValue("stage", "runpod_status")
                    .addKeyValue("failure", "response_contract")
                    .log("RunPod 상태 응답이 없습니다.");
            throw new RunpodStatusUnavailableException();
        }

        return response;
    }
}
