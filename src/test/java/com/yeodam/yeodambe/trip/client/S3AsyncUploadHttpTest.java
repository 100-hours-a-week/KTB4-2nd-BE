package com.yeodam.yeodambe.trip.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class S3AsyncUploadHttpTest {
    @Test
    void 실제_PUT은_파일을_전송하고_응답이_오기_전까지_Future가_완료되지_않는다(
            @TempDir Path directory
    ) throws Exception {
        String previousKey = System.getProperty("aws.accessKeyId");
        String previousSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "test-access");
        System.setProperty("aws.secretAccessKey", "test-secret");
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> tagging = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            tagging.set(exchange.getRequestHeaders().getFirst("x-amz-tagging"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.countDown();
            try {
                if (respond.await(10, TimeUnit.SECONDS)) {
                    exchange.getResponseHeaders().set("ETag", "\"test\"");
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        S3TripAttachmentStorageClient storage = null;
        try {
            storage = new S3TripAttachmentStorageClient("test-bucket", "ap-northeast-2",
                    Duration.ofMinutes(10), "http://127.0.0.1:" + server.getAddress().getPort(), true, metrics);
            Path file = Files.writeString(directory.resolve("analyze.jpg"), "test-image-payload");
            CompletableFuture<String> result = storage.storeDerivedToAsync("planned/analyze.jpg", file, "image/jpeg");
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isNotDone();
            assertThat(path.get()).isEqualTo("/test-bucket/planned/analyze.jpg");
            assertThat(tagging.get()).isEqualTo("status=retained");
            assertThat(body.get()).contains("test-image-payload");
            respond.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("planned/analyze.jpg");
        } finally {
            respond.countDown();
            if (storage != null) {
                storage.close();
            }
            server.stop(0);
            metrics.close();
            restore("aws.accessKeyId", previousKey);
            restore("aws.secretAccessKey", previousSecret);
        }
    }

    private void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
