package com.yeodam.yeodambe.trip.client;


import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TripAttachmentStorageClient {
    void delete(String objectKey);

    long size(String objectKey);

    InputStream open(String objectKey);

    InputStream openHeader(String objectKey);

    String storeDerived(String executionId, Path file, String mimeType);

    String storeDerivedTo(String objectKey, Path file, String mimeType);

    CompletableFuture<String> storeDerivedToAsync(String objectKey, Path file, String mimeType);

    void retain(List<String> objectKeys);

    String createReadUrl(String objectKey);

    String createDownloadUrl(String objectKey, String originalFileName);

    String storeDownloadArchive(Path archive);

    String createUploadUrl(
            String objectKey,
            String contentType,
            Duration ttl
    );
}
