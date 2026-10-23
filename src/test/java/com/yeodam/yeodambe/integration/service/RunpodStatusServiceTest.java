package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.integration.client.RunpodStatusClient;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunpodStatusServiceTest {
    @Mock
    private RunpodStatusClient client;
    @Mock
    private Clock clock;
    @InjectMocks
    private RunpodStatusService service;

    private Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenAnswer(invocation -> now);
    }

    @Test
    void reusesNullableStatusUntilTenSecondsWithoutExtendingExpiry() {
        var unknown = new RunpodStatusResponse(null, false);
        var ready = new RunpodStatusResponse(true, true);
        when(client.getStatus()).thenReturn(unknown, ready);

        assertThat(service.getStatus()).isEqualTo(unknown);
        now = now.plusSeconds(9);
        assertThat(service.getStatus()).isEqualTo(unknown);
        verify(client, times(1)).getStatus();

        now = now.plusSeconds(1);
        assertThat(service.getStatus()).isEqualTo(ready);
        verify(client, times(2)).getStatus();
    }

    @Test
    void startsCacheLifetimeAfterSlowLookupCompletes() {
        var status = new RunpodStatusResponse(true, false);
        when(client.getStatus()).thenAnswer(invocation -> {
            now = now.plusSeconds(7);
            return status;
        });

        assertThat(service.getStatus()).isEqualTo(status);
        now = now.plusSeconds(9);
        assertThat(service.getStatus()).isEqualTo(status);
        verify(client, times(1)).getStatus();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void suppressesFailedLookupForFiveSecondsAndThenRecovers(boolean emptyResponse) {
        var recovered = new RunpodStatusResponse(true, true);
        when(client.getStatus()).thenAnswer(invocation -> {
            now = now.plusSeconds(2);
            if (emptyResponse) {
                return null;
            }
            throw new RunpodStatusUnavailableException();
        }).thenReturn(recovered);

        assertThatThrownBy(service::getStatus).isInstanceOf(RunpodStatusUnavailableException.class);
        now = now.plusSeconds(4);
        assertThatThrownBy(service::getStatus).isInstanceOf(RunpodStatusUnavailableException.class);
        verify(client, times(1)).getStatus();

        now = now.plusSeconds(1);
        assertThat(service.getStatus()).isEqualTo(recovered);
        assertThat(service.getStatus()).isEqualTo(recovered);
        verify(client, times(2)).getStatus();
    }

    @Test
    void doesNotReturnExpiredReadyStatusWhenRefreshFails() {
        when(client.getStatus()).thenReturn(new RunpodStatusResponse(true, true))
                .thenThrow(new RunpodStatusUnavailableException());

        assertThat(service.getStatus().ready()).isTrue();
        now = now.plusSeconds(10);
        assertThatThrownBy(service::getStatus).isInstanceOf(RunpodStatusUnavailableException.class);
        assertThatThrownBy(service::getStatus).isInstanceOf(RunpodStatusUnavailableException.class);
        verify(client, times(2)).getStatus();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRefreshCallsShareOneLookup(boolean fails) throws Exception {
        var status = new RunpodStatusResponse(true, true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        when(client.getStatus()).thenReturn(status);
        service.getStatus();
        now = now.plusSeconds(10);
        when(client.getStatus()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            if (fails) {
                throw new RunpodStatusUnavailableException();
            }
            return status;
        });

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<RunpodStatusResponse>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.getStatus();
                }));
            }
            start.countDown();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            for (Future<RunpodStatusResponse> result : results) {
                if (fails) {
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(RunpodStatusUnavailableException.class);
                } else {
                    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(status);
                }
            }
        }
        verify(client, times(2)).getStatus();
    }
}
