package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.integration.client.RunpodStatusClient;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunpodStatusService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(5);

    private final RunpodStatusClient client;
    private final Clock clock;

    private RunpodStatusResponse cachedResponse;
    private Instant cacheExpiresAt = Instant.MIN;
    private Instant retryAfter = Instant.MIN;

    public synchronized RunpodStatusResponse getStatus() {
        Instant now = clock.instant();
        if (cachedResponse != null && now.isBefore(cacheExpiresAt)) {
            return cachedResponse;
        }
        if (now.isBefore(retryAfter)) {
            throw new RunpodStatusUnavailableException();
        }

        try {
            RunpodStatusResponse response = fetchStatus();
            cachedResponse = response;
            cacheExpiresAt = clock.instant().plus(CACHE_TTL);
            retryAfter = Instant.MIN;
            return response;
        } catch (RunpodStatusUnavailableException failure) {
            cachedResponse = null;
            retryAfter = clock.instant().plus(FAILURE_COOLDOWN);
            throw failure;
        }
    }

    private RunpodStatusResponse fetchStatus() {
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
