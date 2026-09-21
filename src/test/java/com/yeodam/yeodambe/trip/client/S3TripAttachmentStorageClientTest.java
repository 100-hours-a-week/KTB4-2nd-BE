package com.yeodam.yeodambe.trip.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class S3TripAttachmentStorageClientTest {
    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";

    private String previousAccessKey;
    private String previousSecretKey;
    private S3TripAttachmentStorageClient storageClient;

    @BeforeEach
    void setUp() {
        previousAccessKey = System.getProperty(ACCESS_KEY_PROPERTY);
        previousSecretKey = System.getProperty(SECRET_KEY_PROPERTY);
        System.setProperty(ACCESS_KEY_PROPERTY, "test-access-key");
        System.setProperty(SECRET_KEY_PROPERTY, "test-secret-key");

        storageClient = new S3TripAttachmentStorageClient(
                "test-bucket",
                "ap-northeast-2",
                Duration.ofMinutes(10)
        );
    }

    @AfterEach
    void tearDown() {
        storageClient.close();
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

    private void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
            return;
        }

        System.setProperty(name, previousValue);
    }
}
