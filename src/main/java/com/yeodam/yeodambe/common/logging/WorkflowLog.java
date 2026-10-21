package com.yeodam.yeodambe.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowLog {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowLog.class);
    private static final ScopedValue<BatchTimings> BATCH = ScopedValue.newInstance();

    private WorkflowLog() {
    }

    public static <T> T collect(BatchTimings batch, java.util.function.Supplier<T> work) {
        return ScopedValue.where(BATCH, batch).call(work::get);
    }

    public static Stage stage(String stage) {
        return new Stage(stage);
    }

    public static void finished(
            String workflow,
            Long tripId,
            String jobId,
            LocalDateTime createdAt,
            String result,
            String errorCode
    ) {
        observed(workflow, tripId, jobId, createdAt, LocalDateTime.now(), "workflow_finished", result, errorCode);
    }

    public static void finishedFromDatabase(
            String workflow,
            Long tripId,
            String jobId,
            LocalDateTime createdAt,
            LocalDateTime databaseNow,
            String result,
            String errorCode
    ) {
        observed(workflow, tripId, jobId, createdAt, databaseNow, "workflow_finished", result, errorCode);
    }

    public static void event(String workflow, Long tripId, String jobId, String event) {
        try (var scope = WorkflowMdcScope.job(workflow, tripId, jobId)) {
            stage(event).committed(event);
        }
    }

    public static void deliveryObserved(String workflow, Long tripId, String jobId, String messageType) {
        try (var scope = WorkflowMdcScope.job(workflow, tripId, jobId)) {
            stage("ai_request_observed").field("message_type", messageType).committed("ai_request_observed");
        }
    }

    public static void batchAccepted(String workflow, Long tripId, String jobId, int batchNo) {
        try (var scope = WorkflowMdcScope.job(workflow, tripId, jobId)) {
            stage("upload_batch_accepted").field("batch_no", batchNo).committed("upload_batch_accepted");
        }
    }

    public static void uploadCompleted(
            String workflow, Long tripId, String jobId, LocalDateTime createdAt, LocalDateTime referenceNow
    ) {
        observed(workflow, tripId, jobId, createdAt, referenceNow, "original_upload_completed", "success", null);
    }

    private static void observed(
            String workflow, Long tripId, String jobId, LocalDateTime createdAt, LocalDateTime referenceNow,
            String event, String result, String errorCode
    ) {
        try (var scope = WorkflowMdcScope.job(workflow, tripId, jobId)) {
            Stage timing = stage(event).field("error_code", errorCode);
            Long baselineMillis = createdAt == null || referenceNow == null
                    ? null : Duration.between(createdAt, referenceNow).toMillis();
            if (baselineMillis != null && baselineMillis >= 0) {
                // DB 생성 시각은 같은 DB 시계와 비교하고, 이후 커밋 대기는 단조 시계로 잰다.
                timing.field("stage_started_at", timing.startedAt.minusMillis(baselineMillis).toString());
            }
            afterCommit(() -> {
                if (baselineMillis != null && baselineMillis >= 0) {
                    long elapsed = baselineMillis + (System.nanoTime() - timing.startedNanos) / 1_000_000;
                    timing.field("workflow_elapsed_ms", elapsed);
                    if ("workflow_finished".equals(event)) {
                        timing.field("duration_ms", elapsed);
                    }
                } else if (baselineMillis != null) {
                    timing.field("failure", "clock_mismatch");
                }
                timing.emit(event, result, false);
            }, () -> { });
        }
    }

    private static void afterCommit(Runnable committed, Runnable rolledBack) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committed.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    rolledBack.run();
                }
            }
        });
    }

    public static final class BatchTimings {
        private final Map<String, StageTotals> stages = new LinkedHashMap<>();
        private boolean reported;

        private synchronized void record(
                String stage, Instant startedAt, Instant finishedAt, long start, long end, boolean success
        ) {
            var sample = new StageTotals(startedAt, finishedAt, start, end, end - start, 1, success ? 0 : 1);
            stages.merge(stage, sample, StageTotals::merge);
        }

        public synchronized void log() {
            if (reported) {
                return;
            }
            reported = true;
            stages.forEach((name, totals) -> stage(name)
                    .field("stage_started_at", totals.startedAt().toString())
                    .field("stage_finished_at", totals.finishedAt().toString())
                    .field("work_duration_ms", totals.workNanos() / 1_000_000)
                    .field("stage_elapsed_ms", (totals.lastNanos() - totals.firstNanos()) / 1_000_000)
                    .field("sample_count", totals.samples())
                    .field("failed_count", totals.failures())
                    .emit("photo_stage_summary", totals.failures() == 0 ? "success" : "failure", false));
        }
    }

    private record StageTotals(
            Instant startedAt,
            Instant finishedAt,
            long firstNanos,
            long lastNanos,
            long workNanos,
            long samples,
            long failures
    ) {
        private StageTotals merge(StageTotals other) {
            return new StageTotals(firstNanos <= other.firstNanos ? startedAt : other.startedAt,
                    lastNanos >= other.lastNanos ? finishedAt : other.finishedAt,
                    Math.min(firstNanos, other.firstNanos), Math.max(lastNanos, other.lastNanos),
                    workNanos + other.workNanos, samples + other.samples, failures + other.failures);
        }
    }

    public static final class Stage implements AutoCloseable {
        private final String stage;
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private final Map<String, String> context = MDC.getCopyOfContextMap();
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private boolean completed;
        private boolean debug;
        private BatchTimings batch = BATCH.isBound() ? BATCH.get() : null;

        private Stage(String stage) {
            this.stage = stage;
        }

        public Stage field(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public Stage recordTo(BatchTimings batch) {
            this.batch = batch;
            return this;
        }

        public Stage debug() {
            debug = true;
            return this;
        }

        public void success() {
            completed = true;
            emit(stage, "success", true);
        }

        public void committed(String event) {
            completed = true;
            afterCommit(() -> emit(event, "success", true), () -> {
                field("failure_stage", stage);
                emit(event, "failure", true);
            });
        }

        public void failure(String failureStage, String errorCode) {
            completed = true;
            field("failure_stage", failureStage).field("error_code", errorCode);
            emit(stage, "failure", true);
        }

        private void emit(String event, String result, boolean measureDuration) {
            if (batch != null && debug && measureDuration) {
                batch.record(stage, startedAt, Instant.now(), startedNanos, System.nanoTime(), "success".equals(result));
            }
            try (var scope = WorkflowMdcScope.open(context)) {
                var builder = "failure".equals(result) ? LOG.atWarn() : debug ? LOG.atDebug() : LOG.atInfo();
                builder.addKeyValue("event", event).addKeyValue("stage", stage)
                        .addKeyValue("result", result)
                        .addKeyValue("stage_started_at", fields.getOrDefault("stage_started_at", startedAt.toString()))
                        .addKeyValue("stage_finished_at", fields.getOrDefault("stage_finished_at", Instant.now().toString()));
                if (measureDuration) {
                    builder.addKeyValue("duration_ms", (System.nanoTime() - startedNanos) / 1_000_000);
                }
                fields.forEach((key, value) -> {
                    if (!"stage_started_at".equals(key) && !"stage_finished_at".equals(key)) {
                        builder.addKeyValue(key, value);
                    }
                });
                builder.log("워크플로 처리 단계");
            }
        }

        @Override
        public void close() {
            if (!completed) {
                failure(stage, null);
            }
        }
    }
}
