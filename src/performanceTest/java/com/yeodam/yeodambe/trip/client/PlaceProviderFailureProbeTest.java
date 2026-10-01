package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaceProviderFailureProbeTest {
    private enum Failure { HTTP_ERROR, TIMEOUT, RESPONSE_FORMAT, PROVIDER_RESULT, OTHER_TRANSPORT, UNEXPECTED }

    private record Attempt(int number, Instant startedAt, long durationMs, Failure failure,
                           String httpStatus, String providerResultCode, String errorType) {
    }

    @Test
    void 실패_원인을_구분한다() {
        assertThat(classify(new PlaceQueryProviderUnavailableException(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))))
                .isEqualTo(Failure.HTTP_ERROR);
        assertThat(classify(new PlaceQueryProviderUnavailableException(
                new ResourceAccessException("timeout", new HttpTimeoutException("timeout")))))
                .isEqualTo(Failure.TIMEOUT);
        assertThat(classify(new PlaceQueryProviderUnavailableException("응답 구조가 올바르지 않습니다.")))
                .isEqualTo(Failure.RESPONSE_FORMAT);
        assertThat(classify(new PlaceQueryProviderUnavailableException("행안부 API 오류: 23")))
                .isEqualTo(Failure.PROVIDER_RESULT);
    }

    @Test
    void 실제_외부_제공자_검색의_실패_유형별_건수를_저장한다() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("PLACE_PROBE_RUN")),
                "실제 API 부하 테스트는 PLACE_PROBE_RUN=true일 때만 실행합니다.");
        String url = System.getenv("DATA_GO_KR_LEGAL_DISTRICT_API_URL");
        String key = System.getenv("DATA_GO_KR_SERVICE_KEY");
        assumeTrue(url != null && !url.isBlank() && key != null && !key.isBlank(),
                "실제 API URL과 인증키가 설정된 경우에만 실행합니다.");

        int samples = Integer.parseInt(System.getenv().getOrDefault("PLACE_PROBE_SAMPLES", "30"));
        int concurrency = Integer.parseInt(System.getenv().getOrDefault("PLACE_PROBE_CONCURRENCY", "3"));
        assertThat(samples).isBetween(1, 30);
        assertThat(concurrency).isBetween(1, 30);
        PlaceClient client = new PlaceClient(url, key, Duration.ofSeconds(3), new ObjectMapper());
        List<Attempt> attempts = new java.util.ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            for (int first = 1; first <= samples; first += concurrency) {
                List<Callable<Attempt>> batch = IntStream.range(first, Math.min(samples + 1, first + concurrency))
                        .<Callable<Attempt>>mapToObj(number -> () -> search(client, number))
                        .toList();
                for (Future<Attempt> result : executor.invokeAll(batch)) {
                    attempts.add(result.get());
                }
                if (attempts.stream().anyMatch(attempt -> "429".equals(attempt.httpStatus()))) {
                    break;
                }
            }
        }
        Path report = writeCsv(attempts, concurrency);
        EnumMap<Failure, Integer> failures = new EnumMap<>(Failure.class);
        Map<String, Integer> httpStatuses = new java.util.TreeMap<>();
        for (Attempt attempt : attempts) {
            if (attempt.failure() != null) {
                failures.merge(attempt.failure(), 1, Integer::sum);
            }
            if (!attempt.httpStatus().isEmpty()) {
                httpStatuses.merge(attempt.httpStatus(), 1, Integer::sum);
            }
        }
        System.err.printf("PLACE_PROVIDER_PROBE requested=%d attempted=%d success=%d failures=%s httpStatuses=%s csv=%s%n",
                samples, attempts.size(), attempts.size() - failures.values().stream().mapToInt(Integer::intValue).sum(),
                failures, httpStatuses, report.toAbsolutePath());
        assertThat(failures.getOrDefault(Failure.UNEXPECTED, 0)).isZero();
    }

    private static Attempt search(PlaceClient client, int number) {
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        try {
            client.search("제주", 1);
            return new Attempt(number, startedAt, elapsedMs(start), null, "", "", "");
        } catch (PlaceQueryProviderUnavailableException e) {
            String status = "";
            String errorType = e.getClass().getSimpleName();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                errorType = cause.getClass().getSimpleName();
                if (cause instanceof HttpStatusCodeException httpError) {
                    status = String.valueOf(httpError.getStatusCode().value());
                    break;
                }
            }
            String prefix = "행안부 API 오류: ";
            String code = e.getMessage() != null && e.getMessage().startsWith(prefix)
                    ? e.getMessage().substring(prefix.length()).replaceAll("[^A-Za-z0-9_-]", "") : "";
            return new Attempt(number, startedAt, elapsedMs(start), classify(e), status, code, errorType);
        } catch (RuntimeException e) {
            return new Attempt(number, startedAt, elapsedMs(start), Failure.UNEXPECTED,
                    "", "", e.getClass().getSimpleName());
        }
    }

    private static long elapsedMs(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    private static Path writeCsv(List<Attempt> attempts, int concurrency) throws IOException {
        Path directory = Path.of("build", "reports", "place-provider");
        Files.createDirectories(directory);
        Path file = directory.resolve("attempts-" + Instant.now().toEpochMilli() + ".csv");
        StringBuilder csv = new StringBuilder("concurrency,attempt,started_at,duration_ms,outcome,failure_type,http_status,provider_result_code,error_type\n");
        for (Attempt attempt : attempts) {
            csv.append(concurrency).append(',').append(attempt.number()).append(',')
                    .append(attempt.startedAt()).append(',')
                    .append(attempt.durationMs()).append(',')
                    .append(attempt.failure() == null ? "SUCCESS" : "FAILURE").append(',')
                    .append(attempt.failure() == null ? "" : attempt.failure()).append(',')
                    .append(attempt.httpStatus()).append(',').append(attempt.providerResultCode()).append(',')
                    .append(attempt.errorType()).append('\n');
        }
        Files.writeString(file, csv);
        return file;
    }

    private static Failure classify(PlaceQueryProviderUnavailableException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpStatusCodeException) {
                return Failure.HTTP_ERROR;
            }
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return Failure.TIMEOUT;
            }
            if (cause instanceof JacksonException) {
                return Failure.RESPONSE_FORMAT;
            }
        }
        if (exception.getMessage() != null && exception.getMessage().startsWith("행안부 API 오류:")) {
            return Failure.PROVIDER_RESULT;
        }
        return exception.getCause() == null ? Failure.RESPONSE_FORMAT : Failure.OTHER_TRANSPORT;
    }
}
