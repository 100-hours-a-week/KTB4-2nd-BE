package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.Gauge;
import java.util.LinkedHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripAttachmentDerivativeService {
    private final TripAttachmentStorageClient storage;
    private final ObjectMapper json;
    private final MeterRegistry meterRegistry;
    private final TripDerivativeScheduler scheduler;

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
        long submitted = System.nanoTime();
        AtomicInteger attempted = new AtomicInteger();
        event(executionId, -1, "submit", submitted, "attempt", originalKeys.size(), null);
        count("batches", "submitted", 1);
        CompletableFuture<List<DerivedPhotoKeys>> result = new CompletableFuture<>();
        List<ArrayList<String>> uploadedByPhoto = new ArrayList<>();
        List<TripDerivativeScheduler.PhotoTask> tasks = new ArrayList<>();
        for (int index = 0; index < originalKeys.size(); index++) {
            int photoIndex = index;
            String original = originalKeys.get(index);
            String mime = mimeTypes.get(index);
            ArrayList<String> uploaded = new ArrayList<>();
            uploadedByPhoto.add(uploaded);
            tasks.add(new TripDerivativeScheduler.PhotoTask(index, mime, () -> {
                if (callerMdc == null) MDC.clear();
                else MDC.setContextMap(callerMdc);
                MDC.put("worker_batch_id", callerMdc == null ? executionId : callerMdc.getOrDefault("request_id", executionId));
                MDC.put("job_id", executionId);
                MDC.put("worker_photo_index", String.valueOf(photoIndex));
                MDC.put("worker_format", mime);
                long started = System.nanoTime();
                if (attempted.getAndIncrement() == 0) {
                    event(executionId, -1, "start", submitted, "accepted", originalKeys.size(), null);
                    meterRegistry.timer("yeodam.image.worker.queue.wait").record(started - submitted, TimeUnit.NANOSECONDS);
                }
                count("photos", "attempted", 1);
                event(executionId, photoIndex, "photo_start", started, "attempt", 1, null);
                RuntimeException error = null;
                try {
                    DerivedPhotoKeys photo = generateOne(executionId, original, mime, uploaded);
                    count("photos", "generated", 1);
                    return photo;
                } catch (RuntimeException failure) {
                    error = failure;
                    count("photos", Thread.currentThread().isInterrupted() ? "cancelled" : "failure", 1);
                    throw failure;
                } finally {
                    event(executionId, photoIndex, "photo_end", started,
                            error == null ? "success" : Thread.currentThread().isInterrupted() ? "cancelled" : "failure", 1, error);
                    MDC.clear();
                }
            }));
        }
        // 내부 완료는 모든 사진의 저장/임시 파일 정리가 끝난 신호다. 외부 Future 취소와 분리한다.
        CompletableFuture<List<DerivedPhotoKeys>> scheduled;
        try {
            scheduled = scheduler.submit(executionId, tasks, () -> !result.isCancelled() && active.getAsBoolean());
        } catch (RejectedExecutionException failure) {
            count("batches", "rejected", 1);
            event(executionId, -1, "rejected", submitted, "rejected", originalKeys.size(), failure);
            throw failure;
        }
        count("batches", "accepted", 1);
        count("photos", "accepted", originalKeys.size());
        scheduled
                .whenComplete((photos, failure) -> {
                    count("photos", "unexecuted", originalKeys.size() - attempted.get());
                    String outcome = failure == null && !result.isCancelled() ? "success"
                            : result.isCancelled() || failure instanceof CancellationException || Thread.currentThread().isInterrupted() ? "cancelled" : "failure";
                    count("batches", outcome, 1);
                    Map<String, String> completionMdc = MDC.getCopyOfContextMap();
                    try {
                        if (callerMdc == null) MDC.clear(); else MDC.setContextMap(callerMdc);
                        event(executionId, -1, "end", submitted, outcome, originalKeys.size(), failure);
                    } finally {
                        if (completionMdc == null) MDC.clear(); else MDC.setContextMap(completionMdc);
                    }
                    if (failure == null && result.complete(photos)) {
                        count("photos", "committed", photos.size());
                        return;
                    }
                    Throwable cause = failure == null ? new CancellationException("사진 변환이 취소됐습니다.") : failure;
                    for (List<String> uploaded : uploadedByPhoto) {
                        for (String key : uploaded) {
                            try { storage.delete(key); }
                            catch (RuntimeException cleanupFailure) { cause.addSuppressed(cleanupFailure); }
                        }
                    }
                    result.completeExceptionally(cause);
                });
        return result;
    }

    private double workerGauge(String name) {
        return meterRegistry.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    @PostConstruct
    void registerWorkerGauges() {
        Gauge.builder("yeodam.image.worker.queue", this, value -> value.workerGauge("yeodam.trip.derivative.pending.photos")).register(meterRegistry);
        Gauge.builder("yeodam.image.worker.active", this, value -> value.workerGauge("yeodam.trip.derivative.active.photos")).register(meterRegistry);
    }

    private void count(String kind, String outcome, int amount) {
        meterRegistry.counter("yeodam.image.worker." + kind, "outcome", outcome).increment(amount);
    }

    private void event(String executionId, int index, String stage, long start, String outcome,
                       int count, Throwable failure) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("batch_id", MDC.get("request_id") == null ? executionId : MDC.get("request_id"));
        values.put("execution_id", executionId);
        values.put("photo_index", index);
        values.put("stage", stage);
        values.put("start_ns", start);
        values.put("end_ns", System.nanoTime());
        values.put("epoch_ms", System.currentTimeMillis());
        values.put("outcome", outcome);
        values.put("photo_count", count);
        if (MDC.get("worker_format") != null) values.put("format", MDC.get("worker_format"));
        if (MDC.get("worker_mp") != null) values.put("mp", MDC.get("worker_mp"));
        values.put("queue_size", workerGauge("yeodam.trip.derivative.pending.photos"));
        values.put("active_batches", workerGauge("yeodam.trip.derivative.active.photos"));
        if (failure != null) values.put("error_type", failure.getClass().getSimpleName());
        log.atInfo().addKeyValue("event", "image_worker").addKeyValue("job_id", executionId)
                .addKeyValue("worker", values).log("이미지 워커 단계");
    }

    private void stage(String stage, long started, Throwable failure) {
        String outcome = failure == null ? "success" : "failure";
        meterRegistry.timer("yeodam.image.worker.stage", "stage", stage, "outcome", outcome)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        event(MDC.get("job_id"), Integer.parseInt(MDC.get("worker_photo_index") == null ? "-1" : MDC.get("worker_photo_index")), stage, started, outcome, 1, failure);
    }

    public void cancelExecution(String executionId) { scheduler.cancelExecution(executionId); }

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
            long readStart = System.nanoTime();
            Throwable readFailure = null;
            Timer.Sample readSample = Timer.start(meterRegistry);
            String readOutcome = "success";

            try {
                try (InputStream input = storage.open(originalKey)) {
                    Files.copy(input, original);
                }
            } catch (IOException | RuntimeException failure) {
                readOutcome = "failure";
                readFailure = failure;
                throw failure;
            } finally {
                stage("original_read", readStart, readFailure);
                readSample.stop(Timer.builder("yeodam.trip.stage")
                        .tags("stage", "original_s3_read", "outcome", readOutcome)
                        .register(meterRegistry));
            }

            Timer.Sample derivativeSample = Timer.start(meterRegistry);
            String derivativeOutcome = "success";

            try {
                JsonNode metadata = metadata(original);
                if (metadata.has("ImageWidth") && metadata.has("ImageHeight")) {
                    MDC.put("worker_mp", String.valueOf(metadata.path("ImageWidth").asLong() * metadata.path("ImageHeight").asLong() / 1_000_000.0));
                }
                convertDerivatives(original, mimeType, metadata, analyze, preview, display);

                // 필요한 촬영 정보만 AI용 JPEG에 복사한다. 회전은 이미 픽셀에 반영됐다.
                run("exif_copy", "exiftool", "-overwrite_original",
                        "-TagsFromFile", original.toString(),
                        "-DateTimeOriginal", "-SubSecTimeOriginal",
                        "-OffsetTimeOriginal", "-GPS:All", "-Make", "-Model",
                        "-Orientation#=1", analyze.toString());

                String analyzeKey;
                try {
                    analyzeKey = put(executionId, analyze, "image/jpeg", "put_analyze");
                } catch (AttachmentStorageException failure) {
                    uploadedKeys.add(failure.getObjectKey());
                    throw failure;
                }
                uploadedKeys.add(analyzeKey);
                String previewKey;
                try {
                    previewKey = put(executionId, preview, "image/webp", "put_preview");
                } catch (AttachmentStorageException failure) {
                    uploadedKeys.add(failure.getObjectKey());
                    throw failure;
                }
                uploadedKeys.add(previewKey);

                String displayKey = null;
                if ("image/heic".equals(mimeType)) {
                    try {
                        displayKey = put(executionId, display, "image/jpeg", "put_display");
                    } catch (AttachmentStorageException failure) {
                        uploadedKeys.add(failure.getObjectKey());
                        throw failure;
                    }
                    uploadedKeys.add(displayKey);
                }

                return new DerivedPhotoKeys(originalKey, analyzeKey, previewKey, displayKey,
                        takenAt(metadata), coordinate(metadata, "GPSLatitude", 90),
                        coordinate(metadata, "GPSLongitude", 180), deviceModel(metadata));
            } catch (RuntimeException failure) {
                derivativeOutcome = "failure";
                throw failure;
            } finally {
                derivativeSample.stop(Timer.builder("yeodam.trip.stage")
                        .tags("stage", "image_derivative", "outcome", derivativeOutcome)
                        .register(meterRegistry));
            }
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

    private String put(String executionId, Path file, String mimeType, String name) {
        long started = System.nanoTime();
        RuntimeException error = null;
        try { return storage.storeDerived(executionId, file, mimeType); }
        catch (RuntimeException failure) { error = failure; throw failure; }
        finally { stage(name, started, error); }
    }

    private JsonNode metadata(Path original) {
        long started = System.nanoTime();
        Throwable error = null;
        Path output = original.resolveSibling("metadata.json");
        Process process = null;
        try {
            process = processBuilder("exif_extract", "exiftool", "-j", "-n",
                    "-Orientation", "-DateTimeOriginal", "-OffsetTimeOriginal",
                    "-GPSLatitude", "-GPSLongitude",
                    "-Make", "-Model", "-ImageWidth", "-ImageHeight", original.toString())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(commandWaitSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_FAILED.message());
            }
            JsonNode values = json.readTree(Files.readAllBytes(output));
            if (!values.isArray() || values.isEmpty()) throw new IllegalStateException(TripInternalErrorMessage.EXIF_RESULT_MISSING.message());
            return values.get(0);
        } catch (IOException e) {
            error = e;
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_TOOL_EXECUTION_FAILED.message(), e);
        } catch (InterruptedException e) {
            error = e;
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripInternalErrorMessage.EXIF_EXTRACTION_INTERRUPTED.message(), e);
        } catch (RuntimeException e) { error = e; throw e; }
        finally {
            stopProcess(process);
            stage("exif_extract", started, error);
            try { Files.deleteIfExists(output); }
            catch (IOException failure) { log.warn("임시 EXIF 파일 삭제 실패: {}", output, failure); }
        }
    }

    private OffsetDateTime takenAt(JsonNode metadata) {
        String date = metadata.path("DateTimeOriginal").asString();
        String offset = metadata.path("OffsetTimeOriginal").asString();
        if (date.isBlank()) return null;
        if (offset.isBlank()) offset = "+09:00"; // 시간대가 없는 카메라 시각은 한국 현지 시계로 해석
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
        command.add("convert");
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
        run("convert_derivatives", command.toArray(String[]::new));
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

    private ProcessBuilder processBuilder(String stage, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        String directory = System.getenv("YEODAM_PERF_COMMAND_DIR");
        if (directory != null) {
            builder.command().set(0, Path.of(directory, command[0]).toString());
            for (String key : List.of("request_id", "job_id", "worker_batch_id", "worker_photo_index", "worker_format")) {
                String value = MDC.get(key);
                if (value != null) builder.environment().put("YEODAM_PERF_" + key.toUpperCase(java.util.Locale.ROOT), value);
            }
            builder.environment().put("YEODAM_PERF_STAGE", stage);
        }
        return builder;
    }

    private long commandWaitSeconds() {
        if (System.getenv("YEODAM_PERF_COMMAND_DIR") == null) return 120;
        return Long.parseLong(System.getenv().getOrDefault("YEODAM_PERF_COMMAND_TIMEOUT_SECONDS", "120")) + 10;
    }

    private void run(String name, String... command) {
        long started = System.nanoTime();
        Throwable error = null;
        Process process = null;
        try {
            process = processBuilder(name, command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!process.waitFor(commandWaitSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_TIMEOUT.message().formatted(command[0]));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(TripInternalErrorMessage.COMMAND_FAILED.message().formatted(command[0]));
            }
        } catch (IOException e) {
            error = e;
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_UNAVAILABLE.message().formatted(command[0]), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = e;
            throw new IllegalStateException(TripInternalErrorMessage.COMMAND_INTERRUPTED.message().formatted(command[0]), e);
        } catch (RuntimeException e) { error = e; throw e; }
        finally { stopProcess(process); stage(name, started, error); }
    }

    private void stopProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        // 프로세스가 살아 있으면 원본/임시 파일 보상을 시작할 수 없다.
        while (process.isAlive()) {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    log.error("사진 변환 프로세스 종료 지연: pid={}", process.pid());
                }
            } catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

}
