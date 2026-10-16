package com.yeodam.yeodambe.trip.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class S3HeaderReadHttpTest {
    private HttpServer server;
    private S3TripAttachmentStorageClient storage;
    private SimpleMeterRegistry metrics;
    private byte[] body;
    private int status = 200;
    private final AtomicReference<String> requestedRange = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicInteger sentBytes = new AtomicInteger();

    @BeforeEach
    void startLocalServer() throws Exception {
        body = new byte[1024 * 1024];
        body[0] = (byte) 0xff;
        body[1] = (byte) 0xd8;
        body[2] = (byte) 0xff;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            requestedRange.set(range);
            requestedPath.set(exchange.getRequestURI().getPath());
            boolean partial = status == 200 && "bytes=0-11".equals(range);
            byte[] response = partial ? Arrays.copyOf(body, Math.min(12, body.length)) : body;
            if (partial) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes 0-" + (response.length - 1) + "/" + body.length);
            }
            exchange.sendResponseHeaders(partial ? 206 : status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
                sentBytes.addAndGet(response.length);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var s3 = S3Client.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local-test", "local-test")))
                .build();
        metrics = new SimpleMeterRegistry();
        storage = new S3TripAttachmentStorageClient(s3, "test-bucket",
                mock(S3Presigner.class), Duration.ofMinutes(10), metrics);
    }

    @AfterEach
    void stopLocalServer() {
        if (storage != null) storage.close();
        if (metrics != null) metrics.close();
        if (server != null) server.stop(0);
    }

    @Test
    void headerReadRequestsOnlyFirstTwelveBytes() throws Exception {
        try (var input = storage.openHeader("original/photo.jpg")) {
            assertThat(input.readAllBytes()).isEqualTo(Arrays.copyOf(body, 12));
        }
        assertThat(requestedRange.get()).isEqualTo("bytes=0-11");
        assertThat(requestedPath.get()).isEqualTo("/test-bucket/original/photo.jpg");
    }

    @Test
    void shortObjectIsNotPaddedToTwelveBytes() throws Exception {
        body = new byte[]{1, 2};
        try (var input = storage.openHeader("short")) {
            assertThat(input.readNBytes(12)).containsExactly((byte) 1, (byte) 2);
        }
        assertThat(requestedRange.get()).isEqualTo("bytes=0-11");
    }

    @Test
    void originalReadStillRequestsAndReturnsEntireObject() throws Exception {
        try (var input = storage.open("original/photo.jpg")) {
            assertThat(input.readAllBytes()).isEqualTo(body);
        }
        assertThat(requestedRange.get()).isNull();
    }

    @Test
    void missingObjectErrorIsNotConvertedToEmptyHeader() {
        status = 404;
        body = "<Error><Code>NoSuchKey</Code><Message>missing</Message></Error>".getBytes();
        assertThatThrownBy(() -> storage.openHeader("missing"))
                .isInstanceOfSatisfying(S3Exception.class,
                        failure -> assertThat(failure.statusCode()).isEqualTo(404));
        assertThat(requestedRange.get()).isEqualTo("bytes=0-11");
    }
}
