package com.yeodam.yeodambe.trip.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3StorageLoggingTest {
    private final S3Client s3 = mock(S3Client.class);
    private final S3AsyncClient asyncS3 = mock(S3AsyncClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
            s3, asyncS3, "bucket", mock(S3Presigner.class), Duration.ofMinutes(10), meters
    );
    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowLog.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        MDC.setContextMap(Map.of("request_id", "request-1", "workflow", "trip_creation", "job_id", "execution-1"));
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
        client.close();
        meters.close();
    }

    @Test
    void 객체_삭제_성공과_실패를_키와_요청_ID로_기록한다() {
        client.delete("original/key");
        assertTiming("s3_object_delete", "original/key", "success");
        logs.list.clear();
        RuntimeException failure = new IllegalStateException("secret-key https://private-url");
        doThrow(failure).when(s3).deleteObject(org.mockito.ArgumentMatchers.<Consumer<DeleteObjectRequest.Builder>>any());

        assertThatThrownBy(() -> client.delete("original/key")).isSameAs(failure);

        assertTiming("s3_object_delete", "original/key", "failure");
    }

    @Test
    void 동기_파생본_업로드의_객체별_완료를_기록한다() throws IOException {
        Path file = Files.write(directory.resolve("private-name.jpg"), new byte[]{1, 2});
        when(s3.putObject(org.mockito.ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String generatedKey = client.storeDerived("execution-1", file, "image/jpeg");
        assertTiming("s3_derived_upload", generatedKey, "success");
        logs.list.clear();
        assertThat(client.storeDerivedTo("derived/key", file, "image/jpeg")).isEqualTo("derived/key");
        assertTiming("s3_derived_upload", "derived/key", "success");
    }

    @Test
    void 비동기_업로드는_실제_완료_후_원래_MDC로_기록하고_콜백_스레드_MDC를_복원한다() throws Exception {
        Path file = Files.write(directory.resolve("private-name.jpg"), new byte[]{1, 2});
        CompletableFuture<PutObjectResponse> upload = new CompletableFuture<>();
        when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class))).thenReturn(upload);

        CompletableFuture<String> result = client.storeDerivedToAsync("derived/key", file, "image/jpeg");
        assertThat(result).isNotDone();
        assertThat(logs.list).isEmpty();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Map<String, String> restored = executor.submit(() -> {
                MDC.setContextMap(Map.of("request_id", "worker-request"));
                try {
                    upload.complete(PutObjectResponse.builder().build());
                    return MDC.getCopyOfContextMap();
                } finally {
                    MDC.clear();
                }
            }).get();
            assertThat(restored).containsExactlyEntriesOf(Map.of("request_id", "worker-request"));
        }

        assertThat(result.join()).isEqualTo("derived/key");
        assertTiming("s3_derived_upload", "derived/key", "success");
        assertThat(file).exists();
    }

    @Test
    void 비동기_업로드_실패에도_객체_키와_파일을_보존하고_실패_시간을_기록한다() throws IOException {
        Path file = Files.write(directory.resolve("private-name.jpg"), new byte[]{1});
        CompletableFuture<PutObjectResponse> upload = new CompletableFuture<>();
        when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class))).thenReturn(upload);
        CompletableFuture<String> result = client.storeDerivedToAsync("derived/key", file, "image/jpeg");

        upload.completeExceptionally(new IOException("secret-key https://private-url"));

        assertThatThrownBy(result::join).hasCauseInstanceOf(AttachmentStorageException.class);
        assertTiming("s3_derived_upload", "derived/key", "failure");
        assertThat(file).exists();
    }

    @Test
    void SDK가_즉시_실패해도_실패한_Future와_객체_기록을_남긴다() throws IOException {
        Path file = Files.write(directory.resolve("private-name.jpg"), new byte[]{1});
        when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenThrow(new IllegalStateException("secret-key"));

        CompletableFuture<String> result = client.storeDerivedToAsync("derived/key", file, "image/jpeg");

        assertThatThrownBy(result::join).hasCauseInstanceOf(AttachmentStorageException.class);
        assertTiming("s3_derived_upload", "derived/key", "failure");
        assertThat(file).exists();
    }

    @Test
    void 반환된_Future가_취소되어도_S3_전송_완료_기록을_남긴다() throws IOException {
        Path file = Files.write(directory.resolve("private-name.jpg"), new byte[]{1});
        CompletableFuture<PutObjectResponse> upload = new CompletableFuture<>();
        when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class))).thenReturn(upload);
        CompletableFuture<String> result = client.storeDerivedToAsync("derived/key", file, "image/jpeg");

        result.cancel(false);
        assertThat(logs.list).isEmpty();
        upload.complete(PutObjectResponse.builder().build());

        assertThat(result).isCancelled();
        assertTiming("s3_derived_upload", "derived/key", "success");
        assertThat(file).exists();
    }

    private void assertTiming(String stage, String key, String result) {
        ILoggingEvent event = logs.list.stream()
                .filter(log -> stage.equals(field(log, "stage")))
                .findFirst().orElseThrow();
        assertThat(field(event, "object_key")).isEqualTo(key);
        assertThat(field(event, "result")).isEqualTo(result);
        assertThat(field(event, "duration_ms")).isInstanceOf(Long.class);
        assertThat((Long) field(event, "duration_ms")).isGreaterThanOrEqualTo(0L);
        assertThat(event.getMDCPropertyMap()).containsEntry("request_id", "request-1").containsEntry("job_id", "execution-1");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getKeyValuePairs().toString()).doesNotContain("secret-key", "private-url", "private-name");
    }

    private Object field(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst().orElse(null);
    }
}
