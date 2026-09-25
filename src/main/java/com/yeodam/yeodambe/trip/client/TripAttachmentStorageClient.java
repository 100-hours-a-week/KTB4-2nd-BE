package com.yeodam.yeodambe.trip.client;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface TripAttachmentStorageClient {
    String store(String executionId, MultipartFile file);

    void delete(String objectKey);

    InputStream open(String objectKey);

    String storeDerived(String executionId, Path file, String mimeType);

    void retain(List<String> objectKeys);

    String createReadUrl(String objectKey);

    String createDownloadUrl(String objectKey, String originalFileName);
}
