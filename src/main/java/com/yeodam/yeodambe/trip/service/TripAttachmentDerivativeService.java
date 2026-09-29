package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.MDC;

import java.util.Map;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripAttachmentDerivativeService {
    private final TripAttachmentStorageClient storage;
    private final ObjectMapper json;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2)
    );

    public CompletableFuture<List<DerivedPhotoKeys>> createAll(
            String executionId, List<String> originalKeys, List<String> mimeTypes
    ) {
        if (originalKeys.size() != mimeTypes.size()) {
            throw new IllegalArgumentException(TripInternalErrorMessage.SOURCE_KEY_MIME_TYPE_COUNT_MISMATCH.message());
        }
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        return CompletableFuture.supplyAsync(() -> {
            if (callerMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(callerMdc);
            }

            try {
                return generateAll(executionId, originalKeys, mimeTypes);
            } finally {
                MDC.clear();
            }
        }, worker);
    }

    private List<DerivedPhotoKeys> generateAll(
            String executionId, List<String> originalKeys, List<String> mimeTypes
    ) {
        List<DerivedPhotoKeys> results = new ArrayList<>();
        ArrayList<String> uploadedKeys = new ArrayList<>();

        try {
            for (int i = 0; i < originalKeys.size(); i++) {
                results.add(generateOne(
                        executionId, originalKeys.get(i), mimeTypes.get(i), uploadedKeys));
            }
            return List.copyOf(results);
        } catch (RuntimeException failure) {
            for (String key : uploadedKeys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private DerivedPhotoKeys generateOne(
            String executionId,
            String originalKey,
            String mimeType,
            ArrayList<String> uploadedKeys
    ) {
        Path dir;
        try {
            dir = Files.createTempDirectory("사진 작업 디렉터리를 만들 수 없습니다.");
        } catch (IOException e) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_WORK_DIRECTORY_CREATE_FAILED.message(), e);
        }

        Path original = dir.resolve("original");
        Path analyze = dir.resolve("analyze.jpg");
        Path preview = dir.resolve("preview.webp");
        Path display = dir.resolve("display.jpg");

        try {
            try (InputStream input = storage.open(originalKey)) {
                Files.copy(input, original);
            }
            JsonNode metadata = metadata(original);
            String orientation = orientation(metadata);

            // 방향 보정 → 비율 유지·긴 변 최대 1024px → 메타데이터 제거
            run("convert", original + "[0]", "-orient", orientation, "-auto-orient",
                    "-resize", "1024x1024>", "-strip",
                    "-background", "white", "-alpha", "remove", "-alpha", "off",
                    analyze.toString());

            // 필요한 촬영 정보만 AI용 JPEG에 복사한다. 회전은 이미 픽셀에 반영됐다.
            run("exiftool", "-overwrite_original",
                    "-TagsFromFile", original.toString(),
                    "-DateTimeOriginal", "-SubSecTimeOriginal",
                    "-OffsetTimeOriginal", "-GPS:All", "-Make", "-Model",
                    "-Orientation=1", analyze.toString());

            // 미리보기에는 EXIF를 복사하지 않는다.
            run("convert", original + "[0]", "-orient", orientation, "-auto-orient",
                    "-resize", "1024x1024>", "-strip",
                    "-quality", "75", preview.toString());

            String analyzeKey;
            try {
                analyzeKey = storage.storeDerived(executionId, analyze, "image/jpeg");
            } catch (AttachmentStorageException failure) {
                uploadedKeys.add(failure.getObjectKey());
                throw failure;
            }
            uploadedKeys.add(analyzeKey);
            String previewKey;
            try {
                previewKey = storage.storeDerived(executionId, preview, "image/webp");
            } catch (AttachmentStorageException failure) {
                uploadedKeys.add(failure.getObjectKey());
                throw failure;
            }
            uploadedKeys.add(previewKey);

            String displayKey = null;
            if ("image/heic".equals(mimeType)) {
                run("convert", original + "[0]", "-orient", orientation, "-auto-orient",
                        "+profile", "exif", "-quality", "95", display.toString());
                try {
                    displayKey = storage.storeDerived(executionId, display, "image/jpeg");
                } catch (AttachmentStorageException failure) {
                    uploadedKeys.add(failure.getObjectKey());
                    throw failure;
                }
                uploadedKeys.add(displayKey);
            }

            return new DerivedPhotoKeys(originalKey, analyzeKey, previewKey, displayKey,
                    takenAt(metadata), coordinate(metadata, "GPSLatitude", 90),
                    coordinate(metadata, "GPSLongitude", 180), deviceModel(metadata));
        } catch (Exception e) {
            throw new IllegalStateException(TripInternalErrorMessage.DERIVED_ATTACHMENT_CREATE_FAILED.message(), e);
        } finally {
            for (Path path : List.of(display, preview, analyze, original, dir)) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    log.warn("임시 사진 파일 삭제 실패: {}", path, cleanupFailure);
                }
            }
        }
    }

    private JsonNode metadata(Path original) {
        try {
            Process process = new ProcessBuilder("exiftool", "-j", "-n",
                    "-Orientation", "-DateTimeOriginal", "-OffsetTimeOriginal",
                    "-GPSLatitude", "-GPSLongitude",
                    "-Make", "-Model", original.toString()).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_FAILED.message());
            }
            JsonNode values = json.readTree(output);
            if (!values.isArray() || values.isEmpty()) throw new IllegalStateException(TripInternalErrorMessage.EXIF_RESULT_MISSING.message());
            return values.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_TOOL_EXECUTION_FAILED.message(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_INTERRUPTED.message(), e);
        }
    }

    private OffsetDateTime takenAt(JsonNode metadata) {
        String date = metadata.path("DateTimeOriginal").asString();
        String offset = metadata.path("OffsetTimeOriginal").asString();
        if (date.isBlank() || offset.isBlank()) return null;
        try {
            return OffsetDateTime.parse(date.substring(0, 4) + "-" + date.substring(5, 7)
                    + "-" + date.substring(8, 10) + "T" + date.substring(11) + offset);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String orientation(JsonNode metadata) {
        return switch (metadata.path("Orientation").asInt(1)) {
            case 2 -> "TopRight";
            case 3 -> "BottomRight";
            case 4 -> "BottomLeft";
            case 5 -> "LeftTop";
            case 6 -> "RightTop";
            case 7 -> "RightBottom";
            case 8 -> "LeftBottom";
            default -> "TopLeft";
        };
    }

    private BigDecimal coordinate(JsonNode metadata, String field, int maximum) {
        JsonNode value = metadata.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            BigDecimal coordinate = new BigDecimal(value.asString());
            return coordinate.abs().compareTo(BigDecimal.valueOf(maximum)) <= 0 ? coordinate : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String deviceModel(JsonNode metadata) {
        String make = metadata.path("Make").asString();
        String model = metadata.path("Model").asString();
        String combined = (make + " " + model).trim();
        return combined.isEmpty() || combined.length() > 100 ? null : combined;
    }

    private void run(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_TIMEOUT.message().formatted(command[0]));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_FAILED.message().formatted(command[0]));
            }
        } catch (IOException e) {
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_UNAVAILABLE.message().formatted(command[0]), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_INTERRUPTED.message().formatted(command[0]), e);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
