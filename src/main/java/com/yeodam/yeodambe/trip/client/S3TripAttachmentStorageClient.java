package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@Component
public class S3TripAttachmentStorageClient implements TripAttachmentStorageClient {
    private final S3Client s3;
    private final String bucket;

    public S3TripAttachmentStorageClient(
            @Value("${attachment.s3.bucket}") String bucket,
            @Value("${aws.region}") String region
    ) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public String store(String executionId, MultipartFile file) {
        String key = "trip-uploads/" + executionId + "/original/" + UUID.randomUUID();

        try (InputStream input = file.getInputStream()) {
            s3.putObject(
                    request -> request.bucket(bucket).key(key).tagging("status=temporary"),
                    RequestBody.fromInputStream(input, file.getSize())
            );
            return key;
        } catch (IOException | RuntimeException failure) {
            throw new AttachmentStorageException(key, failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        s3.deleteObject(request -> request
                .bucket(bucket)
                .key(objectKey));
    }

    @Override
    public InputStream open(String objectKey) {
        return s3.getObject(request -> request
                .bucket(bucket)
                .key(objectKey));
    }

    @Override
    public String storeDerived(String executionId, Path file, String mimeType) {
        if (!mimeType.equals("image/jpeg") && !mimeType.equals("image/webp")) {
            throw new IllegalArgumentException("지원하지 않는 파생 파일 형식입니다.");
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
    }
}
