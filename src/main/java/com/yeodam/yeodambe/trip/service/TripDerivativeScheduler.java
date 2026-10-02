package com.yeodam.yeodambe.trip.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Slf4j
@Component
public class TripDerivativeScheduler {
    public record PhotoTask(int index, String mimeType, Callable<DerivedPhotoKeys> action) {}

    // ponytail: 한 잠금과 한도 내 사진 순회; 접수 경합이 측정되면 포맷별 상태 분리 검토.
    private final Object lock = new Object();
    private final Set<Batch> batches = new LinkedHashSet<>();
    private final Lane heic;
    private final Lane light;
    private boolean stopping;

    public TripDerivativeScheduler(
            @Value("${attachment.derivative.heic-workers:1}") int heicWorkers,
            @Value("${attachment.derivative.light-workers:1}") int lightWorkers,
            @Value("${attachment.derivative.heic-max-inflight-photos:10}") int heicLimit,
            @Value("${attachment.derivative.light-max-inflight-photos:20}") int lightLimit,
            MeterRegistry meters
    ) {
        if (heicWorkers < 1 || lightWorkers < 1 || heicLimit < heicWorkers || lightLimit < lightWorkers) {
            throw new IllegalArgumentException("사진 접수 한도는 양수 워커 수 이상이어야 합니다.");
        }
        heic = new Lane("heic", heicWorkers, heicLimit);
        light = new Lane("light", lightWorkers, lightLimit);
    }

    public CompletableFuture<List<DerivedPhotoKeys>> submit(
            String executionId, List<PhotoTask> tasks, BooleanSupplier active
    ) {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(active);
        tasks = List.copyOf(tasks);
        boolean[] indices = new boolean[tasks.size()];
        int heavyCount = 0;
        for (PhotoTask task : tasks) {
            Objects.requireNonNull(task.action());
            if (task.index() < 0 || task.index() >= tasks.size() || indices[task.index()]) {
                throw new IllegalArgumentException("사진 index는 중복 없이 0부터 연속해야 합니다.");
            }
            indices[task.index()] = true;
            if (lane(task.mimeType()) == heic) heavyCount++;
        }
        if (!active.getAsBoolean()) return CompletableFuture.failedFuture(new CancellationException("사진 변환이 취소됐습니다."));
        List<Batch> ready = new ArrayList<>();
        Batch batch;
        synchronized (lock) {
            if (stopping || heavyCount > heic.limit - heic.inflight || tasks.size() - heavyCount > light.limit - light.inflight) {
                throw new RejectedExecutionException("사진 변환 접수 한도를 초과했습니다.");
            }
            batch = new Batch(executionId, tasks, active);
            heic.inflight += heavyCount;
            light.inflight += tasks.size() - heavyCount;
            batches.add(batch);
            scheduleNext(batch, heic);
            scheduleNext(batch, light);
            collectReady(batch, ready);
        }
        publish(ready);
        return batch.future;
    }

    public void cancelExecution(String executionId) {
        List<Batch> ready = new ArrayList<>();
        synchronized (lock) {
            for (Batch batch : List.copyOf(batches)) {
                if (batch.executionId.equals(executionId)) {
                    fail(batch, new CancellationException("사진 변환이 취소됐습니다."));
                    collectReady(batch, ready);
                }
            }
        }
        publish(ready);
    }

    private Lane lane(String mime) {
        return switch (Objects.requireNonNull(mime)) {
            case "image/heic" -> heic;
            case "image/jpeg", "image/png" -> light;
            default -> throw new IllegalArgumentException("지원하지 않는 사진 포맷입니다.");
        };
    }

    // 잠금 안에서 제출하므로 완료 후 재제출보다 먼저 접수된 배치가 FIFO 차례를 얻는다.
    private void scheduleNext(Batch batch, Lane lane) {
        if (batch.failure != null) return;
        for (Job job : batch.jobs) {
            if (job.lane == lane && job.state == State.NEW) {
                job.state = State.QUEUED;
                try {
                    lane.executor.execute(job);
                } catch (RejectedExecutionException failure) {
                    job.state = State.NEW;
                    fail(batch, failure);
                }
                return;
            }
        }
    }

    private void fail(Batch batch, Throwable failure) {
        if (batch.failure == null) batch.failure = failure;
        for (Job job : batch.jobs) {
            if (job.state == State.NEW || (job.state == State.QUEUED && job.lane.executor.remove(job))) {
                finish(job);
            }
        }
    }

    private void finish(Job job) {
        if (job.state == State.ACTIVE) job.lane.active--;
        job.state = State.DONE;
        job.lane.inflight--;
        job.batch.remaining--;
    }

    private void collectReady(Batch batch, List<Batch> ready) {
        if (batch.remaining == 0 && batches.remove(batch)) ready.add(batch);
    }

    private void publish(List<Batch> ready) {
        for (Batch batch : ready) {
            if (batch.failure == null) batch.future.complete(List.copyOf(Arrays.asList(batch.results)));
            else batch.future.completeExceptionally(batch.failure);
        }
    }

    @PreDestroy
    void stop() { stop(60, TimeUnit.SECONDS); }

    void stop(long timeout, TimeUnit unit) {
        List<Batch> ready = new ArrayList<>();
        synchronized (lock) {
            stopping = true;
            for (Batch batch : List.copyOf(batches)) {
                fail(batch, new CancellationException("사진 변환 실행기를 종료합니다."));
                collectReady(batch, ready);
            }
            heic.executor.shutdown();
            light.executor.shutdown();
        }
        publish(ready);
        boolean interrupted = false;
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        try {
            for (Lane lane : List.of(heic, light)) {
                lane.executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException failure) { interrupted = true; }
        for (Lane lane : List.of(heic, light)) {
            if (!lane.executor.isTerminated()) {
                List<Runnable> removed = lane.executor.shutdownNow();
                ready = new ArrayList<>();
                synchronized (lock) {
                    for (Runnable runnable : removed) {
                        Job job = (Job) runnable;
                        if (job.state == State.QUEUED) finish(job);
                        collectReady(job.batch, ready);
                    }
                }
                publish(ready);
            }
        }
        deadline = System.nanoTime() + unit.toNanos(timeout);
        try {
            for (Lane lane : List.of(heic, light)) {
                if (!lane.executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    log.error("사진 변환 워커 종료 실패: lane={}", lane.name);
                }
            }
        } catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private enum State { NEW, QUEUED, ACTIVE, DONE }

    private static class Lane {
        final String name;
        final int limit;
        final ThreadPoolExecutor executor;
        int inflight;
        int active;
        Lane(String name, int workers, int limit) {
            this.name = name;
            this.limit = limit;
            executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(limit), runnable -> new Thread(runnable, "photo-" + name),
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    private class Batch {
        final String executionId;
        final BooleanSupplier active;
        final List<Job> jobs;
        final DerivedPhotoKeys[] results;
        final CompletableFuture<List<DerivedPhotoKeys>> future = new CompletableFuture<>();
        int remaining;
        Throwable failure;
        Batch(String executionId, List<PhotoTask> tasks, BooleanSupplier active) {
            this.executionId = executionId;
            this.active = active;
            this.remaining = tasks.size();
            this.results = new DerivedPhotoKeys[tasks.size()];
            this.jobs = tasks.stream().sorted(java.util.Comparator.comparingInt(PhotoTask::index))
                    .map(task -> new Job(this, task, lane(task.mimeType()))).toList();
        }
    }

    private class Job implements Runnable {
        final Batch batch;
        final PhotoTask task;
        final Lane lane;
        State state = State.NEW;
        Job(Batch batch, PhotoTask task, Lane lane) { this.batch = batch; this.task = task; this.lane = lane; }

        @Override
        public void run() {
            List<Batch> ready = new ArrayList<>();
            boolean execute;
            synchronized (lock) {
                execute = batch.failure == null;
                if (execute) { state = State.ACTIVE; lane.active++; }
                else { finish(this); collectReady(batch, ready); }
            }
            if (!execute) { publish(ready); return; }
            DerivedPhotoKeys result = null;
            Throwable failure = null;
            try {
                if (!batch.active.getAsBoolean()) throw new CancellationException("사진 변환이 취소됐습니다.");
                result = Objects.requireNonNull(task.action().call());
                if (!batch.active.getAsBoolean()) throw new CancellationException("사진 변환이 취소됐습니다.");
            } catch (Throwable cause) { failure = cause; }
            synchronized (lock) {
                batch.results[task.index()] = result;
                finish(this);
                if (failure != null) fail(batch, failure);
                scheduleNext(batch, lane);
                collectReady(batch, ready);
            }
            publish(ready);
        }
    }
}
