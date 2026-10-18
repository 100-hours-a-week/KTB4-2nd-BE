package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3TripAttachmentStorageClientTest {
    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";

    private String previousAccessKey;
    private String previousSecretKey;
    private S3TripAttachmentStorageClient storageClient;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        previousAccessKey = System.getProperty(ACCESS_KEY_PROPERTY);
        previousSecretKey = System.getProperty(SECRET_KEY_PROPERTY);
        System.setProperty(ACCESS_KEY_PROPERTY, "test-access-key");
        System.setProperty(SECRET_KEY_PROPERTY, "test-secret-key");
        meterRegistry = new SimpleMeterRegistry();

        storageClient = new S3TripAttachmentStorageClient(
                "test-bucket",
                "ap-northeast-2",
                Duration.ofMinutes(10),
                "",
                false,
                meterRegistry
        );
    }

    @AfterEach
    void tearDown() {
        storageClient.close();
        meterRegistry.close();
        restoreSystemProperty(ACCESS_KEY_PROPERTY, previousAccessKey);
        restoreSystemProperty(SECRET_KEY_PROPERTY, previousSecretKey);
    }

    @Test
    void 업로드_URL에_파일_위치와_필수_헤더와_유효기간을_서명한다() {
        URI uri = URI.create(storageClient.createUploadUrl(
                "trip-uploads/execution/original/photo-key",
                "image/jpeg",
                Duration.ofMinutes(10)
        ));
        String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);

        assertThat(uri.getHost())
                .isEqualTo("test-bucket.s3.ap-northeast-2.amazonaws.com");
        assertThat(uri.getPath())
                .isEqualTo("/trip-uploads/execution/original/photo-key");
        assertThat(query)
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-SignedHeaders=content-type;host;if-none-match")
                .contains("X-Amz-Signature=");

        URI shorterUrl = URI.create(storageClient.createUploadUrl(
                "trip-uploads/execution/original/photo-key",
                "image/jpeg",
                Duration.ofMinutes(3)
        ));
        assertThat(shorterUrl.getRawQuery()).contains("X-Amz-Expires=180");
    }

    @Test
    void 객체_키로_십분간_유효한_조회_URL을_만든다() {
        String url = storageClient.createReadUrl(
                "trip-uploads/execution/preview.webp"
        );

        URI uri = URI.create(url);

        assertThat(uri.getHost())
                .isEqualTo("test-bucket.s3.ap-northeast-2.amazonaws.com");
        assertThat(uri.getPath())
                .isEqualTo("/trip-uploads/execution/preview.webp");
        assertThat(uri.getRawQuery())
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=");
    }

    @Test
    void LocalStack_endpoint를_경로_방식_서명_URL에_사용한다() {
        S3TripAttachmentStorageClient localStackClient = new S3TripAttachmentStorageClient(
                "test-bucket",
                "ap-northeast-2",
                Duration.ofMinutes(10),
                "http://localhost:4566",
                true,
                meterRegistry
        );

        try {
            URI uri = URI.create(localStackClient.createReadUrl("trip-uploads/execution/preview.webp"));

            assertThat(uri.getHost()).isEqualTo("localhost");
            assertThat(uri.getPort()).isEqualTo(4566);
            assertThat(uri.getPath()).isEqualTo("/test-bucket/trip-uploads/execution/preview.webp");
        } finally {
            localStackClient.close();
        }
    }

    @Test
    void 로컬_목업_이미지는_S3_서명_URL_대신_정적_리소스_URL을_반환한다() {
        String url = storageClient.createReadUrl("local-map-mock/seoul.png");

        assertThat(url).isEqualTo("http://localhost:8080/api/mock-assets/seoul.png");
    }

    @Test
    void 원본_파일명으로_다운로드_응답_헤더를_포함한_URL을_만든다() {
        String url = storageClient.createDownloadUrl(
                "trip-uploads/execution/original.jpg",
                "서울 여행.jpg"
        );

        URI uri = URI.create(url);
        String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);

        assertThat(uri.getHost())
                .isEqualTo("test-bucket.s3.ap-northeast-2.amazonaws.com");
        assertThat(uri.getPath())
                .isEqualTo("/trip-uploads/execution/original.jpg");
        assertThat(query)
                .contains("response-content-disposition=attachment; filename*=UTF-8''%EC%84%9C%EC%9A%B8%20%EC%97%AC%ED%96%89.jpg")
                .contains("X-Amz-Signature=");
    }

    @Test
    void 객체_HEAD_응답의_실제_바이트를_반환한다() {
        S3Client s3 = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        when(s3.headObject(request.capture()))
                .thenReturn(HeadObjectResponse.builder().contentLength(123L).build());
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                s3, "test-bucket", presigner, Duration.ofMinutes(10), new SimpleMeterRegistry());

        assertThat(client.size("object-key")).isEqualTo(123L);
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().key()).isEqualTo("object-key");
    }

    @Test
    void 비동기_파생본은_전송_완료까지_대기하고_실패한_키를_보존한다(
            @TempDir Path directory
    ) throws Exception {
        S3AsyncClient async = mock(S3AsyncClient.class);
        CompletableFuture<PutObjectResponse> upload = new CompletableFuture<>();
        when(async.putObject(any(PutObjectRequest.class),
                any(AsyncRequestBody.class))).thenReturn(upload);
        Path file = Files.write(directory.resolve("analyze.jpg"), new byte[]{1, 2});
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                mock(S3Client.class), async, "test-bucket", mock(S3Presigner.class),
                Duration.ofMinutes(10), meterRegistry);

        CompletableFuture<String> result =
                client.storeDerivedToAsync("planned/analyze.jpg", file, "image/jpeg");

        assertThat(result).isNotDone();
        ArgumentCaptor<PutObjectRequest> request =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(async).putObject(request.capture(),
                any(AsyncRequestBody.class));
        assertThat(request.getValue().key()).isEqualTo("planned/analyze.jpg");
        assertThat(request.getValue().tagging()).isEqualTo("status=retained");
        upload.completeExceptionally(new IOException("network"));
        assertThatThrownBy(result::join).hasCauseInstanceOf(AttachmentStorageException.class);
        assertThat(((AttachmentStorageException) result.handle((key, error) -> error.getCause()).join())
                .getObjectKey()).isEqualTo("planned/analyze.jpg");
    }

    @Test
    void 비동기_파생본_성공은_예정한_키를_반환한다(
            @TempDir Path directory
    ) throws Exception {
        S3AsyncClient async = mock(S3AsyncClient.class);
        when(async.putObject(any(PutObjectRequest.class),
                any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutObjectResponse.builder().build()));
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                mock(S3Client.class), async, "test-bucket", mock(S3Presigner.class),
                Duration.ofMinutes(10), meterRegistry);
        Path file = Files.write(directory.resolve("preview.webp"), new byte[]{1});

        assertThat(client.storeDerivedToAsync("planned/preview.webp", file, "image/webp").join())
                .isEqualTo("planned/preview.webp");
        assertThatThrownBy(() -> client.storeDerivedToAsync("planned/file", file, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SDK_호출이_즉시_실패해도_실패한_Future와_키를_반환하고_파일을_보존한다(
            @TempDir Path directory
    ) throws Exception {
        S3AsyncClient async = mock(S3AsyncClient.class);
        IllegalStateException failure = new IllegalStateException("client closed");
        when(async.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenThrow(failure);
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                mock(S3Client.class), async, "test-bucket", mock(S3Presigner.class),
                Duration.ofMinutes(10), meterRegistry);
        Path file = Files.write(directory.resolve("analyze.jpg"), new byte[]{1, 2});

        try {
            CompletableFuture<String> result = client.storeDerivedToAsync(
                    "planned/analyze.jpg", file, "image/jpeg");

            assertThatThrownBy(result::join).hasCauseInstanceOf(AttachmentStorageException.class);
            Throwable storageFailure = result.handle((key, error) -> error).join();
            assertThat(storageFailure).isInstanceOf(AttachmentStorageException.class);
            assertThat(((AttachmentStorageException) storageFailure).getObjectKey())
                    .isEqualTo("planned/analyze.jpg");
            assertThat(storageFailure).hasCause(failure);
            assertThat(file).exists();
        } finally {
            client.close();
        }
    }

    private void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
            return;
        }

        System.setProperty(name, previousValue);
    }
}
