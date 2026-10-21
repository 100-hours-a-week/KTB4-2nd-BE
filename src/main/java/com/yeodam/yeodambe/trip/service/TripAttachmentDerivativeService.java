package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.PreparedPhotoDerivative;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final InitialAttachmentStoragePipeline pipeline;
    private final ExifToolProcess exifTool;

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
        transactions.claimBatch().ifPresent(this::startPipeline);
    }

    public void processAcceptedBatch(
            Long tripId,
            Long userId,
            String uploadId
    ) {
        try {
            transactions.claimBatch(tripId, userId, uploadId)
                    .ifPresent(this::startPipeline);
        } catch (RuntimeException failure) {
            logConversionSubmissionFailure(tripId, uploadId, failure);
        }
    }

    private void startPipeline(InitialAttachmentUploadBatchSnapshot claim) {
        pipeline.start(claim, (photo, keys) -> prepareInitialPhoto(photo.objectKey(), photo.contentType(), keys),
                this::isTransientConversionFailure);
    }

    private PreparedPhotoDerivative prepareInitialPhoto(
            String originalKey,
            String mimeType,
            List<String> keys
    ) {
        Path directory;
        try {
            directory = Files.createTempDirectory("initial-photo-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        try {
            downloadOriginal(originalKey, directory.resolve("original"));
            return prepareLocal(originalKey, mimeType, keys, directory);
        } catch (IOException | RuntimeException failure) {
            PreparedPhotoDerivative.cleanup(directory);
            throw new IllegalStateException(TripInternalErrorMessage.DERIVED_ATTACHMENT_CREATE_FAILED.message(), failure);
        }
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
        pipeline.cancelExecution(executionId);
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
                    logTemporaryPhotoCleanupFailure(path, cleanupFailure);
                }
            }
        }
    }

    private void downloadOriginal(
            String originalKey,
            Path original
    ) throws IOException {
        try (var timing = startOriginalDownloadLogging()) {
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

            timing.success();
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
        Timer.Sample derivativeSample = Timer.start(meterRegistry);
        String derivativeOutcome = "success";

        try {
            PreparedPhotoDerivative prepared = prepareLocal(originalKey, mimeType, plannedKeys, directory);
            List<String> stored = new ArrayList<>();
            try (var uploadTiming = startDerivativeUploadLogging()) {
                for (int index = 0; index < prepared.outputs().size(); index++) {
                    PreparedPhotoDerivative.Output output = prepared.outputs().get(index);
                    stored.add(storeTrackedDerivative(executionId, output.file(), output.mimeType(),
                            plannedKeys, index, uploadedKeys));
                }
                uploadTiming.success();
            }
            return prepared.storedKeys(stored);
        } catch (RuntimeException failure) {
            derivativeOutcome = "failure";
            throw failure;
        } finally {
            derivativeSample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "image_derivative", "outcome", derivativeOutcome)
                    .register(meterRegistry));
        }
    }

    private PreparedPhotoDerivative prepareLocal(
            String originalKey,
            String mimeType,
            List<String> keys,
            Path directory
    ) {
        try (var timing = startImageConversionLogging()) {
            Path original = directory.resolve("original");
            Path analyze = directory.resolve("analyze.jpg");
            Path preview = directory.resolve("preview.webp");
            Path display = directory.resolve("display.jpg");
            JsonNode metadata = metadata(original);
            convertDerivatives(original, mimeType, metadata, analyze, preview, display);
            // 필요한 촬영 정보만 복사한다. 방향은 픽셀에 반영했으므로 뷰어의 중복 회전을 막는다.
            ExifToolProcess.Result copyResult = exifTool.execute(List.of(
                    "-overwrite_original", "-TagsFromFile", original.toString(),
                    "-DateTimeOriginal", "-SubSecTimeOriginal",
                    "-OffsetTimeOriginal", "-GPS:All", "-Make", "-Model",
                    "-Orientation#=1", analyze.toString()
            ));

            if (copyResult.exitStatus() != 0) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.COMMAND_FAILED.message()
                                .formatted("exiftool")
                );
            }
            boolean heic = "image/heic".equals(mimeType);
            DerivedPhotoKeys photo = new DerivedPhotoKeys(originalKey, plannedKey(keys, 0), plannedKey(keys, 1),
                    heic ? plannedKey(keys, 2) : null, takenAt(metadata), coordinate(metadata, "GPSLatitude", 90),
                    coordinate(metadata, "GPSLongitude", 180), deviceModel(metadata), fileSize(original),
                    fileSize(analyze), fileSize(preview), heic ? fileSize(display) : null);
            List<PreparedPhotoDerivative.Output> outputs = new ArrayList<>();
            outputs.add(new PreparedPhotoDerivative.Output(analyze, plannedKey(keys, 0), "image/jpeg"));
            outputs.add(new PreparedPhotoDerivative.Output(preview, plannedKey(keys, 1), "image/webp"));
            if (heic) {
                outputs.add(new PreparedPhotoDerivative.Output(display, plannedKey(keys, 2), "image/jpeg"));
            }
            timing.success();
            return new PreparedPhotoDerivative(directory, photo, outputs);
        }
    }

    private String plannedKey(List<String> keys, int index) {
        return keys == null ? null : keys.get(index);
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
        ExifToolProcess.Result result = exifTool.execute(List.of(
                "-j", "-n",
                "-Orientation", "-JPEGQualityEstimate",
                "-DateTimeOriginal", "-OffsetTimeOriginal",
                "-GPSLatitude", "-GPSLongitude",
                "-Make", "-Model",
                original.toString()
        ));

        if (result.exitStatus() != 0) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.EXIF_EXTRACTION_FAILED.message()
            );
        }

        JsonNode values = json.readTree(result.output());

        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.EXIF_RESULT_MISSING.message()
            );
        }

        return values.get(0);
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
        VipsAttachmentConverter.convert(original, mimeType, analyze, preview, display,
                metadata.path("JPEGQualityEstimate").asInt(92));
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

    private WorkflowLog.Stage startOriginalDownloadLogging() {
        return WorkflowLog.stage("original_download").debug();
    }

    private WorkflowLog.Stage startDerivativeUploadLogging() {
        return WorkflowLog.stage("derivative_upload").debug();
    }

    private WorkflowLog.Stage startImageConversionLogging() {
        return WorkflowLog.stage("image_conversion").debug();
    }

    private void logConversionSubmissionFailure(Long tripId, String uploadId, RuntimeException failure) {
        log.warn("접수한 사진 배치의 즉시 제출 재시도: tripId={}, uploadId={}, stage=conversion_submit, cause={}",
                tripId, uploadId, failure.getClass().getSimpleName());
    }

    private void logTemporaryPhotoCleanupFailure(Path path, IOException cleanupFailure) {
        log.warn("임시 사진 파일 삭제 실패: {}", path, cleanupFailure);
    }
}
