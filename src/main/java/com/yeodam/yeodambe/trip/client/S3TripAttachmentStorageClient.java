package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@Component
public class S3TripAttachmentStorageClient implements TripAttachmentStorageClient {
    private static final String LOCAL_MOCK_ASSET_PREFIX = "local-map-mock/";
    private static final String LOCAL_MOCK_ASSET_URL_PREFIX = "http://localhost:8080/api/mock-assets/";

    private final S3Client s3;
    private final String bucket;
    private final S3Presigner presigner;
    private final Duration readUrlTtl;
    private final MeterRegistry meterRegistry;

    @Autowired
    public S3TripAttachmentStorageClient(
            @Value("${attachment.s3.bucket}") String bucket,
            @Value("${aws.region}") String region,
            @Value("${attachment.s3.read-url-ttl}") Duration readUrlTtl,
            @Value("${attachment.s3.endpoint}") String endpoint,
            @Value("${attachment.s3.path-style}") boolean pathStyle,
            MeterRegistry meterRegistry
    ) {
        this.bucket = bucket;
        this.readUrlTtl = readUrlTtl;
        this.meterRegistry = meterRegistry;
        var s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle)
                .build();

        var s3Builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(s3Configuration);

        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(s3Configuration);

        if (!endpoint.isBlank()) {
            URI endpointUri = URI.create(endpoint);
            s3Builder.endpointOverride(endpointUri);
            presignerBuilder.endpointOverride(endpointUri);
        }

        this.s3 = s3Builder.build();
        this.presigner = presignerBuilder.build();
    }

    S3TripAttachmentStorageClient(
            S3Client s3,
            String bucket,
            S3Presigner presigner,
            Duration readUrlTtl,
            MeterRegistry meterRegistry
    ) {
        this.s3 = s3;
        this.bucket = bucket;
        this.presigner = presigner;
        this.readUrlTtl = readUrlTtl;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String store(String executionId, MultipartFile file) {
        String key = "trip-uploads/" + executionId + "/original/" + UUID.randomUUID();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";

        try (InputStream input = file.getInputStream()) {
            s3.putObject(
                    request -> request.bucket(bucket).key(key).tagging("status=temporary"),
                    RequestBody.fromInputStream(input, file.getSize())
            );
            return key;
        } catch (IOException | RuntimeException failure) {
            outcome = "failure";
            throw new AttachmentStorageException(key, failure);
        } finally {
            sample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "original_s3_upload", "outcome", outcome)
                    .register(meterRegistry));
        }
    }

    @Override
    public void delete(String objectKey) {
        s3.deleteObject(request -> request
                .bucket(bucket)
                .key(objectKey));
    }

    @Override
    public long size(String objectKey) {
        return s3.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build())
                .contentLength();
    }

    @Override
    public InputStream open(String objectKey) {
        return s3.getObject(request -> request
                .bucket(bucket)
                .key(objectKey));
    }

    @Override
    public String createReadUrl(String objectKey) {
        if (objectKey.startsWith(LOCAL_MOCK_ASSET_PREFIX)) {
            return LOCAL_MOCK_ASSET_URL_PREFIX
                    + objectKey.substring(LOCAL_MOCK_ASSET_PREFIX.length());
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(readUrlTtl)
                .getObjectRequest(getObjectRequest)
                .build();

        return presigner.presignGetObject(presignRequest)
                .url()
                .toString();
    }

    @Override
    public String createDownloadUrl(String objectKey, String originalFileName) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .responseContentDisposition(
                        "attachment; filename*=UTF-8''" + encodeFileName(originalFileName)
                )
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(readUrlTtl)
                .getObjectRequest(getObjectRequest)
                .build();

        return presigner.presignGetObject(presignRequest)
                .url()
                .toString();
    }

    private String encodeFileName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    @Override
    public String storeDownloadArchive(Path archive) {
        String key = "trip-downloads/" + UUID.randomUUID() + ".zip";

        try {
            s3.putObject(
                    request -> request
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/zip")
                            .tagging("status=temporary"),
                    RequestBody.fromFile(archive)
            );
            return key;
        } catch (RuntimeException failure) {
            throw new AttachmentStorageException(key, failure);
        }
    }

    @Override
    public String storeDerived(String executionId, Path file, String mimeType) {
        if (!mimeType.equals("image/jpeg") && !mimeType.equals("image/webp")) {
            throw new IllegalArgumentException(TripInternalErrorMessage.UNSUPPORTED_DERIVATIVE_FILE_FORMAT.message());
        }

        String key = "trip-uploads/" + executionId + "/derived/" + UUID.randomUUID();
        try {
            s3.putObject(
                    request -> request.bucket(bucket).key(key).contentType(mimeType)
                            .tagging("status=temporary"),
                    RequestBody.fromFile(file)
            );
            return key;
        } catch (RuntimeException failure) {
            throw new AttachmentStorageException(key, failure);
        }
    }

    @Override
    public void retain(List<String> objectKeys) {
        Tagging tagging = Tagging.builder()
                .tagSet(Tag.builder().key("status").value("retained").build())
                .build();
        for (String objectKey : objectKeys) {
            s3.putObjectTagging(request -> request.bucket(bucket).key(objectKey).tagging(tagging));
        }
    }

    @PreDestroy
    void close() {
        s3.close();
        presigner.close();
    }
}
