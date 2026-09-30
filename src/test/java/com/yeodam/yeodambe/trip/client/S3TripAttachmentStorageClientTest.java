package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
    void 원본_S3_업로드_성공을_작업_메트릭으로_기록한다() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(file.getSize()).thenReturn(3L);
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                s3, "test-bucket", presigner, Duration.ofMinutes(10), registry);

        client.store("execution-1", file);

        assertThat(registry.find("yeodam.trip.stage")
                .tags("stage", "original_s3_upload", "outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void 원본_S3_업로드_실패를_작업_메트릭으로_기록한다() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(file.getSize()).thenReturn(3L);
        doThrow(new RuntimeException("S3 failure")).when(s3)
                .putObject(any(java.util.function.Consumer.class), any(RequestBody.class));
        S3TripAttachmentStorageClient client = new S3TripAttachmentStorageClient(
                s3, "test-bucket", presigner, Duration.ofMinutes(10), registry);

        assertThatThrownBy(() -> client.store("execution-1", file))
                .isInstanceOf(AttachmentStorageException.class);

        assertThat(registry.find("yeodam.trip.stage")
                .tags("stage", "original_s3_upload", "outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }

    private void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
            return;
        }

        System.setProperty(name, previousValue);
    }
}
