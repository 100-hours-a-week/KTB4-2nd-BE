package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentDownloadResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkAttachmentDownloadService {

    private static final int MAX_ATTACHMENT_COUNT = 200;
    private static final String ARCHIVE_FILE_NAME = "yeodam-attachments.zip";

    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;

    public BulkAttachmentDownloadResponse issueDownloadUrl(
            Long userId,
            List<Long> tripAttachmentIds
    ) {
        validateIds(tripAttachmentIds);

        List<TripAttachment> attachments = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(tripAttachmentIds);

        if (attachments.size() != tripAttachmentIds.size()
                || attachments.stream()
                .anyMatch(attachment -> !userId.equals(attachment.getTrip().getUserId()))) {
            throw new AttachmentNotFoundException();
        }

        int expectedCount = attachments.size();
        long startedAt = System.nanoTime();
        String failureStage = "zip_create";

        log.atInfo()
                .addKeyValue("event", "attachment_bulk_download")
                .addKeyValue("result", "started")
                .addKeyValue("expected_count", expectedCount)
                .log("첨부 일괄 다운로드를 시작했습니다.");

        Path archive = null;

        try {
            archive = Files.createTempFile("yeodam-attachments-", ".zip");

            failureStage = "source_read";
            createArchive(archive, attachments);

            failureStage = "archive_upload";
            String archiveKey = tripAttachmentStorageClient.storeDownloadArchive(archive);

            failureStage = "presign";
            String downloadUrl = tripAttachmentStorageClient.createDownloadUrl(
                    archiveKey,
                    ARCHIVE_FILE_NAME
            );

            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;

            log.atInfo()
                    .addKeyValue("event", "attachment_bulk_download")
                    .addKeyValue("result", "success")
                    .addKeyValue("expected_count", expectedCount)
                    .addKeyValue("duration_ms", durationMillis)
                    .log("첨부 일괄 다운로드 URL을 발급했습니다.");

            return new BulkAttachmentDownloadResponse(
                    ARCHIVE_FILE_NAME,
                    downloadUrl
            );
        } catch (IOException failure) {
            logBulkDownloadFailure(expectedCount, failureStage, failure);
            throw new IllegalStateException("첨부 ZIP 생성에 실패했습니다.", failure);
        } catch (RuntimeException failure) {
            logBulkDownloadFailure(expectedCount, failureStage, failure);
            throw failure;
        } finally {
            deleteArchive(archive);
        }
    }

    private void validateIds(List<Long> tripAttachmentIds) {
        if (tripAttachmentIds == null
                || tripAttachmentIds.isEmpty()
                || tripAttachmentIds.size() > MAX_ATTACHMENT_COUNT
                || tripAttachmentIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(tripAttachmentIds).size() != tripAttachmentIds.size()) {
            throw new InvalidAttachmentIdsException();
        }
    }

    private void createArchive(
            Path archive,
            List<TripAttachment> attachments
    ) throws IOException {
        Set<String> usedEntryNames = new HashSet<>();

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(
                Files.newOutputStream(archive)
        )) {
            for (TripAttachment attachment : attachments) {
                writeAttachment(zipOutputStream, attachment, usedEntryNames);
            }
        }
    }

    private void writeAttachment(
            ZipOutputStream zipOutputStream,
            TripAttachment attachment,
            Set<String> usedEntryNames
    ) throws IOException {
        String entryName = nextEntryName(
                attachment.getFile().getOriginalFileName(),
                attachment.getId(),
                usedEntryNames
        );

        zipOutputStream.putNextEntry(new ZipEntry(entryName));

        try (InputStream input = tripAttachmentStorageClient.open(
                attachment.getFile().getObjectKey()
        )) {
            input.transferTo(zipOutputStream);
        } finally {
            zipOutputStream.closeEntry();
        }
    }

    private String nextEntryName(
            String originalFileName,
            Long tripAttachmentId,
            Set<String> usedEntryNames
    ) {
        String fileName = normalizeFileName(originalFileName, tripAttachmentId);

        if (usedEntryNames.add(fileName)) {
            return fileName;
        }

        int sequence = 2;
        String candidate;

        do {
            candidate = appendSequence(fileName, sequence);
            sequence++;
        } while (!usedEntryNames.add(candidate));

        return candidate;
    }

    private String normalizeFileName(
            String originalFileName,
            Long tripAttachmentId
    ) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "attachment-" + tripAttachmentId;
        }

        String fileName = originalFileName.replace("\\", "/");
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);

        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            return "attachment-" + tripAttachmentId;
        }

        return fileName;
    }

    private String appendSequence(String fileName, int sequence) {
        int extensionIndex = fileName.lastIndexOf('.');

        if (extensionIndex <= 0) {
            return fileName + " (" + sequence + ")";
        }

        return fileName.substring(0, extensionIndex)
                + " (" + sequence + ")"
                + fileName.substring(extensionIndex);
    }

    private void logBulkDownloadFailure(
            int expectedCount,
            String failureStage,
            Throwable failure
    ) {
        log.atError()
                .addKeyValue("event", "attachment_bulk_download")
                .addKeyValue("result", "failure")
                .addKeyValue("expected_count", expectedCount)
                .addKeyValue("failure_stage", failureStage)
                .addKeyValue("error_code", ErrorMessage.INTERNAL_SERVER_ERROR.name())
                .log("첨부 일괄 다운로드 처리에 실패했습니다.", failure);
    }


    private void deleteArchive(Path archive) {
        if (archive == null) {
            return;
        }

        try {
            Files.deleteIfExists(archive);
        } catch (IOException failure) {
            log.warn("임시 ZIP 파일 삭제에 실패했습니다. path={}", archive, failure);
        }
    }
}