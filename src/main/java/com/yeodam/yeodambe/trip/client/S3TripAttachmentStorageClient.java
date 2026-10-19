package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class S3TripAttachmentStorageClient implements TripAttachmentStorageClient {
    private static final String LOCAL_MOCK_ASSET_PREFIX = "local-map-mock/";
    private static final String LOCAL_MOCK_ASSET_URL_PREFIX = "http://localhost:8080/api/mock-assets/";

    private final S3Client s3;
    private final S3AsyncClient asyncS3;
    private final ThreadPoolExecutor asyncCompletions = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().name("s3-upload-completion").daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());
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
                .serviceConfiguration(s3Configuration)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofMinutes(1)));

        S3AsyncClientBuilder asyncBuilder = S3AsyncClient.builder()
                .asyncConfiguration(config -> config.advancedOption(
                        SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, asyncCompletions))
                .region(Region.of(region))
                .serviceConfiguration(s3Configuration)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(1)
                        .maxPendingConnectionAcquires(2)
                        .eventLoopGroupBuilder(SdkEventLoopGroup.builder().numberOfThreads(1)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofMinutes(1)));

        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(s3Configuration);

        if (!endpoint.isBlank()) {
            URI endpointUri = URI.create(endpoint);
            s3Builder.endpointOverride(endpointUri);
            asyncBuilder.endpointOverride(endpointUri);
            presignerBuilder.endpointOverride(endpointUri);
        }

        this.s3 = s3Builder.build();
        this.asyncS3 = asyncBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    S3TripAttachmentStorageClient(
            S3Client s3,
            String bucket,
            S3Presigner presigner,
            Duration readUrlTtl,
            MeterRegistry meterRegistry
    ) {
        this(s3, null, bucket, presigner, readUrlTtl, meterRegistry);
    }

    S3TripAttachmentStorageClient(
            S3Client s3,
            S3AsyncClient asyncS3,
            String bucket,
            S3Presigner presigner,
            Duration readUrlTtl,
            MeterRegistry meterRegistry
    ) {
        this.s3 = s3;
        this.asyncS3 = asyncS3;
        this.bucket = bucket;
        this.presigner = presigner;
        this.readUrlTtl = readUrlTtl;
        this.meterRegistry = meterRegistry;
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
    public InputStream openHeader(String objectKey) {
        return s3.getObject(request -> request
                .bucket(bucket)
                .key(objectKey)
                .range("bytes=0-11"));
    }

    @Override
    public String createUploadUrl(
            String objectKey,
            String contentType,
            Duration ttl
    ) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .ifNoneMatch("*")
                .build();

        PutObjectPresignRequest presignRequest =
                PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(putObjectRequest)
                        .build();

        return presigner.presignPutObject(presignRequest)
                .url()
                .toString();
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
    public String createDownloadUrl(
            String objectKey,
            String originalFileName
    ) {
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
    public String storeDerived(
            String executionId,
            Path file,
            String mimeType
    ) {
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
    public String storeDerivedTo(
            String objectKey,
            Path file,
            String mimeType
    ) {
        if (!Set.of("image/jpeg", "image/webp").contains(mimeType)) {
            throw new IllegalArgumentException(TripInternalErrorMessage.UNSUPPORTED_DERIVED_ATTACHMENT_FORMAT.message());
        }
        try {
            s3.putObject(request -> request.bucket(bucket).key(objectKey).contentType(mimeType)
                    .tagging("status=retained"), RequestBody.fromFile(file));
            return objectKey;
        } catch (RuntimeException failure) {
            throw new AttachmentStorageException(objectKey, failure);
        }
    }

    @Override
    public CompletableFuture<String> storeDerivedToAsync(
            String objectKey,
            Path file,
            String mimeType
    ) {
        if (!Set.of("image/jpeg", "image/webp").contains(mimeType)) {
            throw new IllegalArgumentException(TripInternalErrorMessage.UNSUPPORTED_DERIVED_ATTACHMENT_FORMAT.message());
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(mimeType)
                .tagging("status=retained")
                .build();
        try {
            return asyncS3.putObject(request, AsyncRequestBody.fromFile(file))
                    .handle((response, failure) -> {
                        if (failure != null) {
                            throw new AttachmentStorageException(objectKey, failure);
                        }
                        return objectKey;
                    });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new AttachmentStorageException(objectKey, failure));
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
        if (asyncS3 != null) {
            asyncS3.close();
        }
        asyncCompletions.shutdown();
        s3.close();
        presigner.close();
    }
}
