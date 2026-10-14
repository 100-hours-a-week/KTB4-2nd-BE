package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripAttachmentDerivativeService {
    private final TripAttachmentStorageClient storage;
    private final ObjectMapper json;
    private final MeterRegistry meterRegistry;
    private final TripDerivativeScheduler scheduler;
    private final InitialAttachmentUploadTransactionService transactions;
    private final ScheduledThreadPoolExecutor leaseScheduler =
            new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().name("initial-photo-lease").daemon(true).factory());

    public CompletableFuture<List<DerivedPhotoKeys>> createAll(
            String executionId, List<String> originalKeys, List<String> mimeTypes
    ) {
        return createAll(executionId, originalKeys, mimeTypes, () -> true);
    }

    public CompletableFuture<List<DerivedPhotoKeys>> createAll(
            String executionId, List<String> originalKeys, List<String> mimeTypes, BooleanSupplier active
    ) {
        if (originalKeys.size() != mimeTypes.size()) {
            throw new IllegalArgumentException(TripInternalErrorMessage.SOURCE_KEY_MIME_TYPE_COUNT_MISMATCH.message());
        }
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        CompletableFuture<List<DerivedPhotoKeys>> result = new CompletableFuture<>();
        List<ArrayList<String>> uploadedByPhoto = new ArrayList<>();
        List<TripDerivativeScheduler.PhotoTask> tasks = new ArrayList<>();
        for (int index = 0; index < originalKeys.size(); index++) {
            String original = originalKeys.get(index);
            String mime = mimeTypes.get(index);
            ArrayList<String> uploaded = new ArrayList<>();
            uploadedByPhoto.add(uploaded);
            tasks.add(new TripDerivativeScheduler.PhotoTask(index, mime, () -> {
                if (callerMdc == null) {
                    MDC.clear();
                }
                else {
                    MDC.setContextMap(callerMdc);
                }
                try {
                    return generateOne(executionId, original, mime, uploaded, null);
                } finally {
                    MDC.clear();
                }
            }));
        }
        // 내부 완료는 모든 사진의 저장/임시 파일 정리가 끝난 신호다. 외부 Future 취소와 분리한다.
        scheduler.submit(executionId, tasks, () -> !result.isCancelled() && active.getAsBoolean())
                .whenComplete((photos, failure) -> {
                    if (failure == null && result.complete(photos)) {
                        return;
                    }
                    Throwable cause = failure == null
                            ? new CancellationException(TripInternalErrorMessage.PHOTO_CONVERSION_CANCELED.message())
                            : failure;
                    for (int i = 0; i < uploadedByPhoto.size(); i++) {
                        try {
                            transactions.scheduleCleanupKeys(originalKeys.get(i), uploadedByPhoto.get(i), "CONVERSION_COMPENSATION");
                        } catch (RuntimeException cleanupFailure) {
                            cause.addSuppressed(cleanupFailure);
                        }
                    }
                    result.completeExceptionally(cause);
                });
        return result;
    }

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.conversion-delay-ms:5000}")
    public void processPendingBatches() {
        transactions.claimBatch().ifPresent(claim -> convertNextPhoto(claim, 0));
    }

    private void convertNextPhoto(
            InitialAttachmentUploadBatchSnapshot claim,
            int from
    ) {
        int index = from;
        while (index < claim.photos().size() && claim.photos().get(index).derived() != null) {
            index++;
        }
        if (index == claim.photos().size()) {
            try {
                transactions.completeBatch(claim);
            } catch (RuntimeException failure) {
                log.warn("사진 배치 저장 재시도: executionId={}, stage=batch_persist, cause={}",
                        claim.executionId(), failure.getClass().getSimpleName());
                transactions.releaseSaturatedBatch(claim);
            }
            return;
        }
        var photo = claim.photos().get(index);
        if (photo.attempts() >= 3) {
            transactions.retryBatch(claim, photo.itemId(), true);
            return;
        }
        ScheduledFuture<?> heartbeat = startLeaseHeartbeat(claim);
        try {
            var task = new TripDerivativeScheduler.PhotoTask(
                    0, photo.contentType(), () -> convertPhoto(claim, photo));
            int next = index + 1;
            scheduler.submit(claim.executionId(), List.of(task), () -> transactions.renewLease(claim))
                    .whenComplete((photos, failure) -> {
                        heartbeat.cancel(false);
                        if (failure == null) {
                            convertNextPhoto(claim, next);
                        } else {
                            log.warn("사진 변환 재시도: executionId={}, itemId={}, stage=image_conversion, cause={}",
                                    claim.executionId(), photo.itemId(), failure.getClass().getSimpleName());
                            transactions.retryBatch(claim, photo.itemId(), !isTransientConversionFailure(failure));
                        }
                    });
        } catch (RejectedExecutionException saturated) {
            heartbeat.cancel(false);
            transactions.releaseSaturatedBatch(claim);
        } catch (RuntimeException failure) {
            heartbeat.cancel(false);
            log.warn("사진 변환 시작 재시도: executionId={}, itemId={}, cause={}",
                    claim.executionId(), photo.itemId(), failure.getClass().getSimpleName());
            transactions.retryBatch(claim, photo.itemId(), !isTransientConversionFailure(failure));
        }
    }

    private ScheduledFuture<?> startLeaseHeartbeat(InitialAttachmentUploadBatchSnapshot claim) {
        return leaseScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!transactions.renewLease(claim)) {
                    scheduler.cancelExecution(claim.executionId());
                }
            } catch (RuntimeException failure) {
                log.warn("사진 변환 선점 갱신 실패: executionId={}, cause={}",
                        claim.executionId(), failure.getClass().getSimpleName());
                scheduler.cancelExecution(claim.executionId());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private DerivedPhotoKeys convertPhoto(
            InitialAttachmentUploadBatchSnapshot claim,
            InitialAttachmentUploadBatchSnapshot.Photo photo
    ) {
        List<String> keys = transactions.startPhotoAttempt(claim, photo.itemId());
        DerivedPhotoKeys derived = generateOne(
                claim.executionId(), photo.objectKey(), photo.contentType(), new ArrayList<>(), keys);
        List<String> retained = new ArrayList<>(keys);
        retained.add(photo.objectKey());
        storage.retain(retained);
        transactions.savePhotoSuccess(claim, photo.itemId(), derived);
        return derived;
    }

    @PreDestroy
    void stopLeaseScheduler() {
        leaseScheduler.shutdownNow();
    }

    public boolean isTransientConversionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof S3Exception s3Failure) {
                return s3Failure.statusCode() >= 500 || s3Failure.statusCode() == 429;
            }
            if (cause instanceof SdkException
                    || cause instanceof IOException
                    || cause instanceof TimeoutException
                    || cause instanceof InterruptedException
                    || cause instanceof CancellationException
                    || cause instanceof DataAccessException) {
                return true;
            }
        }
        return false;
    }

    public void cancelExecution(String executionId) {
        scheduler.cancelExecution(executionId);
    }

    public DerivedPhotoKeys generateAdditional(String executionId, String originalKey,
            String mimeType, List<String> plannedKeys) {
        return generateOne(executionId, originalKey, mimeType, new ArrayList<>(), plannedKeys);
    }

    private DerivedPhotoKeys generateOne(
            String executionId,
            String originalKey,
            String mimeType,
            ArrayList<String> uploadedKeys,
            List<String> plannedKeys
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
            downloadOriginal(originalKey, original);
            return generateDerivatives(executionId, originalKey, mimeType, uploadedKeys, plannedKeys,
                    dir);
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

    private void downloadOriginal(
            String originalKey,
            Path original
    ) throws IOException {
        Timer.Sample readSample = Timer.start(meterRegistry);
        String readOutcome = "success";

        try {
            try (InputStream input = storage.open(originalKey)) {
                Files.copy(input, original);
            }
        } catch (IOException | RuntimeException failure) {
            readOutcome = "failure";
            throw failure;
        } finally {
            readSample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "original_s3_read", "outcome", readOutcome)
                    .register(meterRegistry));
        }
    }

    private DerivedPhotoKeys generateDerivatives(
            String executionId,
            String originalKey,
            String mimeType,
            List<String> uploadedKeys,
            List<String> plannedKeys,
            Path directory
    ) {
        Path original = directory.resolve("original");
        Path analyze = directory.resolve("analyze.jpg");
        Path preview = directory.resolve("preview.webp");
        Path display = directory.resolve("display.jpg");
        Timer.Sample derivativeSample = Timer.start(meterRegistry);
        String derivativeOutcome = "success";

        try {
            JsonNode metadata = metadata(original);
            convertDerivatives(original, mimeType, metadata, analyze, preview, display);

            // 필요한 촬영 정보만 AI용 JPEG에 복사한다. 회전은 이미 픽셀에 반영됐다.
            run("exiftool", "-overwrite_original",
                    "-TagsFromFile", original.toString(),
                    "-DateTimeOriginal", "-SubSecTimeOriginal",
                    "-OffsetTimeOriginal", "-GPS:All", "-Make", "-Model",
                    "-Orientation#=1", analyze.toString());

            String analyzeKey = storeTrackedDerivative(
                    executionId, analyze, "image/jpeg", plannedKeys, 0, uploadedKeys);
            String previewKey = storeTrackedDerivative(
                    executionId, preview, "image/webp", plannedKeys, 1, uploadedKeys);
            String displayKey = null;
            if ("image/heic".equals(mimeType)) {
                displayKey = storeTrackedDerivative(
                        executionId, display, "image/jpeg", plannedKeys, 2, uploadedKeys);
            }

            return new DerivedPhotoKeys(
                    originalKey,
                    analyzeKey,
                    previewKey,
                    displayKey,
                    takenAt(metadata),
                    coordinate(metadata, "GPSLatitude", 90),
                    coordinate(metadata, "GPSLongitude", 180),
                    deviceModel(metadata),
                    fileSize(original),
                    fileSize(analyze),
                    fileSize(preview),
                    displayKey == null ? null : fileSize(display)
            );
        } catch (RuntimeException failure) {
            derivativeOutcome = "failure";
            throw failure;
        } finally {
            derivativeSample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "image_derivative", "outcome", derivativeOutcome)
                    .register(meterRegistry));
        }
    }

    private String storeTrackedDerivative(
            String executionId,
            Path file,
            String mimeType,
            List<String> plannedKeys,
            int index,
            List<String> uploadedKeys
    ) {
        String key;
        try {
            key = plannedKeys == null
                    ? storage.storeDerived(executionId, file, mimeType)
                    : storage.storeDerivedTo(plannedKeys.get(index), file, mimeType);
        } catch (AttachmentStorageException failure) {
            uploadedKeys.add(failure.getObjectKey());
            throw failure;
        }
        uploadedKeys.add(key);
        return key;
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode metadata(Path original) {
        Path output = original.resolveSibling("metadata.json");
        Process process = null;
        try {
            process = new ProcessBuilder("exiftool", "-j", "-n",
                    "-Orientation", "-DateTimeOriginal", "-OffsetTimeOriginal",
                    "-GPSLatitude", "-GPSLongitude",
                    "-Make", "-Model", original.toString())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_FAILED.message(),
                        new TimeoutException(TripInternalErrorMessage.EXIF_PROCESS_TIMEOUT.message()));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_FAILED.message());
            }
            JsonNode values = json.readTree(Files.readAllBytes(output));
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(TripInternalErrorMessage.EXIF_RESULT_MISSING.message());
            }
            return values.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_TOOL_EXECUTION_FAILED.message(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_INTERRUPTED.message(), e);
        } finally {
            stopProcess(process);
            try {
                Files.deleteIfExists(output);
            } catch (IOException failure) {
                log.warn("임시 EXIF 파일 삭제 실패: {}", output, failure);
            }
        }
    }

    private OffsetDateTime takenAt(JsonNode metadata) {
        String date = metadata.path("DateTimeOriginal").asString();
        String offset = metadata.path("OffsetTimeOriginal").asString();
        if (date.isBlank()) {
            return null;
        }
        if (offset.isBlank()) {
            // 시간대가 없는 카메라 시각은 한국 현지 시계로 해석한다.
            offset = "+09:00";
        }
        try {
            return OffsetDateTime.parse(date.substring(0, 4) + "-" + date.substring(5, 7)
                    + "-" + date.substring(8, 10) + "T" + date.substring(11) + offset);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void convertDerivatives(
            Path original, String mimeType, JsonNode metadata, Path analyze, Path preview, Path display
    ) {
        List<String> command = new ArrayList<>();
        command.add("magick");
        command.add("-respect-parentheses");
        command.add(original + "[0]");

        if (!"image/heic".equals(mimeType)) {
            command.add("-orient");
            command.add(orientation(metadata));
            command.add("-auto-orient");
        }
        // 표시본은 축소 전에 분기하고 ICC를 보존한다. clone의 품질 설정은 다른 출력에 전파하지 않는다.
        if ("image/heic".equals(mimeType)) {
            command.addAll(List.of("(", "+clone", "+profile", "exif", "-quality", "95",
                    "-write", display.toString(), "+delete", ")"));
        }
        // 축소 픽셀은 공유하되 분석본의 흰 배경 처리는 WebP 투명도에 영향을 주지 않는다.
        command.addAll(List.of("-resize", "1024x1024>", "-strip",
                "(", "+clone", "-background", "white", "-alpha", "remove", "-alpha", "off",
                "-write", analyze.toString(), "+delete", ")",
                "-quality", "75", preview.toString()));
        run(command.toArray(String[]::new));
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

    private BigDecimal coordinate(
            JsonNode metadata,
            String field,
            int maximum
    ) {
        JsonNode value = metadata.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
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
        Process process = null;
        try {
            process = startProcess(command);

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_TIMEOUT.message().formatted(command[0]),
                        new TimeoutException(TripInternalErrorMessage.PHOTO_CONVERSION_PROCESS_TIMEOUT.message()));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_FAILED.message().formatted(command[0]));
            }
        } catch (IOException e) {
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_UNAVAILABLE.message().formatted(command[0]), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_INTERRUPTED.message().formatted(command[0]), e);
        } finally {
            stopProcess(process);
        }
    }

    private Process startProcess(String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            return builder.start();
        } catch (IOException failure) {
            if (!"magick".equals(command[0])) {
                throw failure;
            }
            // ImageMagick 7이 없는 기존 CI·운영 환경에서는 ImageMagick 6을 사용한다.
            command[0] = "convert";
            try {
                return builder.command(command).start();
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(failure);
                throw fallbackFailure;
            }
        }
    }

    private void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        // 프로세스가 살아 있으면 원본/임시 파일 보상을 시작할 수 없다.
        while (process.isAlive()) {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    log.error("사진 변환 프로세스 종료 지연: pid={}", process.pid());
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
