package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Topics;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Photo;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.attachment-conversion.mode", havingValue = "KAFKA")
@ConditionalOnExpression("!${app.attachment-conversion.operations.enabled:false}")
public class AttachmentConversionWorker {
    private final AttachmentConversionTransactionService db;
    private final TripAttachmentDerivativeService photos;
    private final Topics topics;
    private final long backoffNanos;
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("attachment-conversion-control").daemon(true).factory());
    private final AtomicReferenceArray<Job> registered = new AtomicReferenceArray<>(4);
    private final Job[] active = new Job[Lane.values().length];
    private final Kind[] previous = new Kind[Lane.values().length];
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile boolean stopping;
    private long sequence;

    @Autowired
    public AttachmentConversionWorker(
            AttachmentConversionTransactionService db,
            TripAttachmentDerivativeService photos,
            AttachmentConversionProperties properties,
            @Value("${app.attachment-conversion.retry.technical-backoff}") Duration backoff
    ) {
        this(db, photos, properties.topics(), backoff);
    }

    AttachmentConversionWorker(AttachmentConversionTransactionService db, TripAttachmentDerivativeService photos,
            Topics topics, Duration backoff) {
        Assert.isTrue(backoff != null && backoff.isPositive(), "변환 기술 재시도 간격은 양수여야 합니다.");
        this.db = db;
        this.photos = photos;
        this.topics = topics;
        this.backoffNanos = backoff.toNanos();
        control.setRemoveOnCancelPolicy(true);
    }

    public synchronized CompletableFuture<Void> process(String taskId, Kind kind, Lane lane, String topic, int partition) {
        if (kind == null || lane == null || !topic(kind, lane).equals(topic) || partition < 0 || taskId == null) {
            throw new InvalidAttachmentConversionMessageException();
        }
        if (stopping) {
            throw new RejectedExecutionException("이미지 변환 작업의 접수를 중지했습니다.");
        }
        int slot = slot(kind, lane);
        var existing = registered.get(slot);
        if (existing != null) {
            if (existing.id.equals(taskId) && existing.partition == partition) {
                return existing.completion;
            }
            throw new RejectedExecutionException("해당 토픽의 변환 작업을 이미 관리하고 있습니다.");
        }
        var job = new Job(taskId, kind, lane, topic, partition, sequence++);
        registered.set(slot, job);
        control.execute(this::pump);
        return job.completion;
    }

    public synchronized CompletableFuture<Void> revoke(String topic, Set<Integer> partitions) {
        if (stopping) {
            return drained;
        }
        Set<Integer> revoked = Set.copyOf(partitions);
        var jobs = jobs().stream().filter(job -> job.topic.equals(topic) && revoked.contains(job.partition)).toList();
        jobs.forEach(job -> job.revokeRequested = true);
        control.execute(() -> {
            jobs.forEach(this::revoke);
            pump();
        });
        return CompletableFuture.allOf(jobs.stream().map(job -> job.cleanup).toArray(CompletableFuture[]::new));
    }

    public synchronized void stopAccepting() {
        if (!stopping) {
            stopping = true;
            control.execute(() -> {
                jobs().forEach(this::revoke);
                pump();
            });
        }
    }

    public CompletableFuture<Void> drain() {
        stopAccepting();
        return drained;
    }

    private void revoke(Job job) {
        job.revoked = true;
        if (job.stage == Stage.CLAIM || job.stage == Stage.BEGIN) {
            job.readyAt = System.nanoTime();
        }
    }

    private void pump() {
        for (var job : jobs()) {
            if (!job.busy && job.readyAt - System.nanoTime() <= 0) {
                if (job.revoked && job.stage != Stage.SAVE && job.stage != Stage.FAIL && job.stage != Stage.FINISH) {
                    job.stage = Stage.RELEASE;
                }
                if (job.stage != Stage.BEGIN && job.stage != Stage.PHOTO) {
                    advance(job);
                }
            }
        }
        for (Lane lane : Lane.values()) {
            if (active[lane.ordinal()] == null) {
                var candidates = jobs().stream().filter(job -> job.lane == lane && !stopping && !job.revokeRequested && !job.revoked
                        && job.stage == Stage.BEGIN && job.readyAt - System.nanoTime() <= 0).toList();
                var next = candidates.stream().filter(job -> job.kind != previous[lane.ordinal()])
                        .min(Comparator.comparingLong(job -> job.order))
                        .orElseGet(() -> candidates.stream().min(Comparator.comparingLong(job -> job.order)).orElse(null));
                if (next != null) {
                    active[lane.ordinal()] = next;
                    advance(next);
                }
            }
        }
        finishDrain();
    }

    private void advance(Job job) {
        try {
            switch (job.stage) {
                case CLAIM -> {
                    var result = db.claim(job.id, job.kind, job.lane);
                    switch (result.status()) {
                        case ACK_TERMINAL -> terminal(job);
                        case RETRY_LATER -> retry(job);
                        case CLAIMED -> {
                            job.claim = result.claim();
                            job.index = 0;
                            job.fenced = false;
                            job.heartbeat = control.scheduleWithFixedDelay(() -> renew(job), 30, 30, TimeUnit.SECONDS);
                            nextPhoto(job);
                        }
                    }
                }
                case BEGIN -> begin(job);
                case SAVE -> outcome(job, db.completePhoto(job.claim, job.photo().itemId(), job.output), true);
                case FAIL -> outcome(job, db.recordPhotoFailure(job.claim, job.photo().itemId(),
                        !photos.isTransientConversionFailure(job.failure)), false);
                case FINISH -> {
                    var result = db.finishSubBatch(job.claim);
                    if (result.status() == AttachmentConversionStepResult.Status.ACK_READY) {
                        terminal(job);
                    } else {
                        job.stage = Stage.RELEASE;
                        control.execute(this::pump);
                    }
                }
                case RELEASE -> release(job);
                case PHOTO -> { }
            }
        } catch (InvalidAttachmentConversionMessageException failure) {
            if (job.stage == Stage.CLAIM) {
                job.completion.completeExceptionally(failure);
                remove(job);
            } else {
                retry(job);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("event", "attachment_conversion_retry")
                    .addKeyValue("failure", failure.getClass().getSimpleName()).log("이미지 변환 제어 단계를 재확인합니다.");
            if (job.stage == Stage.BEGIN) {
                releaseLane(job);
            }
            retry(job);
        }
    }

    private void begin(Job job) {
        if (stopping || job.revokeRequested) {
            revoke(job);
            job.stage = Stage.RELEASE;
            release(job);
            return;
        }
        if (!db.renewLease(job.claim)) {
            job.fenced = true;
            job.stage = Stage.FINISH;
            releaseLane(job);
            control.execute(this::pump);
            return;
        }
        job.fenced = false;
        var result = db.beginPhoto(job.claim, job.photo().itemId());
        switch (result.status()) {
            case ACK_READY -> terminal(job);
            case ALREADY_SAVED -> {
                job.index++;
                releaseLane(job);
                nextPhoto(job);
            }
            case RETRY_LATER -> {
                releaseLane(job);
                retry(job);
            }
            case STARTED -> {
                if (stopping || job.revokeRequested) {
                    revoke(job);
                    job.stage = Stage.RELEASE;
                    release(job);
                    return;
                }
                job.stage = Stage.PHOTO;
                job.busy = true;
                previous[job.lane.ordinal()] = job.kind;
                AttachmentConversionClaim claim = job.claim;
                try {
                    photos.processConversionPhoto(claim, job.photo(), result.attempt(), () -> active(job, claim))
                            .whenComplete((output, failure) -> control.execute(() -> photoFinished(job, output, failure)));
                } catch (RuntimeException failure) {
                    photoFinished(job, null, failure);
                }
            }
        }
    }

    private boolean active(Job job, AttachmentConversionClaim claim) {
        if (job.fenced) {
            return false;
        }
        try {
            return db.renewLease(claim);
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("event", "attachment_conversion_guard_retry")
                    .addKeyValue("failure", failure.getClass().getSimpleName()).log("이미지 변환 claim을 재확인합니다.");
            return false;
        }
    }

    private void photoFinished(Job job, DerivedPhotoKeys output, Throwable failure) {
        job.busy = false;
        if (failure == null) {
            job.output = output;
            job.stage = Stage.SAVE;
        } else if (controlFailure(failure)) {
            job.stage = causedBy(failure, RejectedExecutionException.class) ? Stage.BEGIN : Stage.FINISH;
            if (job.stage == Stage.BEGIN) {
                releaseLane(job);
            }
            retry(job);
        } else {
            job.failure = failure;
            job.stage = Stage.FAIL;
        }
        pump();
    }

    private void outcome(Job job, AttachmentConversionStepResult result, boolean saved) {
        if (result.status() == AttachmentConversionStepResult.Status.ACK_READY) {
            terminal(job);
        } else if (result.status() == AttachmentConversionStepResult.Status.CONTINUE) {
            if (saved) {
                job.index++;
            }
            job.output = null;
            job.failure = null;
            releaseLane(job);
            nextPhoto(job);
        } else if (!db.renewLease(job.claim)) {
            job.stage = Stage.RELEASE;
            control.execute(this::pump);
        } else if (saved) {
            retry(job);
        } else {
            job.failure = null;
            job.stage = Stage.BEGIN;
            releaseLane(job);
            retry(job);
        }
    }

    private void nextPhoto(Job job) {
        while (job.index < job.claim.photos().size() && job.photo().derived() != null) {
            job.index++;
        }
        job.stage = job.index == job.claim.photos().size() ? Stage.FINISH : Stage.BEGIN;
        control.execute(this::pump);
    }

    private void renew(Job job) {
        try {
            job.fenced = !db.renewLease(job.claim);
        } catch (RuntimeException failure) {
            job.fenced = true;
            log.atWarn().addKeyValue("event", "attachment_conversion_lease_retry")
                    .addKeyValue("failure", failure.getClass().getSimpleName()).log("이미지 변환 lease를 재확인합니다.");
        }
        if (job.fenced && job.stage == Stage.BEGIN && !job.busy) {
            job.stage = Stage.FINISH;
            control.execute(this::pump);
        }
    }

    private void release(Job job) {
        if (job.claim != null) {
            db.releaseClaim(job.claim);
        }
        releaseLane(job);
        cancelTimers(job);
        job.claim = null;
        job.output = null;
        job.failure = null;
        if (job.revoked) {
            remove(job);
        } else {
            job.stage = Stage.CLAIM;
            retry(job);
        }
    }

    private void terminal(Job job) {
        releaseLane(job);
        remove(job);
        job.completion.complete(null);
    }

    private void remove(Job job) {
        cancelTimers(job);
        registered.compareAndSet(slot(job.kind, job.lane), job, null);
        job.cleanup.complete(null);
        control.execute(this::pump);
    }

    private void releaseLane(Job job) {
        if (active[job.lane.ordinal()] == job) {
            active[job.lane.ordinal()] = null;
        }
    }

    private void retry(Job job) {
        job.readyAt = System.nanoTime() + backoffNanos;
        if (job.retry != null) {
            job.retry.cancel(false);
        }
        job.retry = control.schedule(this::pump, backoffNanos, TimeUnit.NANOSECONDS);
    }

    private void cancelTimers(Job job) {
        if (job.retry != null) {
            job.retry.cancel(false);
        }
        if (job.heartbeat != null) {
            job.heartbeat.cancel(false);
        }
    }

    private List<Job> jobs() {
        return IntStream.range(0, registered.length()).mapToObj(registered::get)
                .filter(Objects::nonNull).toList();
    }

    private synchronized void finishDrain() {
        if (stopping && jobs().isEmpty()) {
            drained.complete(null);
            control.shutdown();
        }
    }

    private boolean controlFailure(Throwable failure) {
        return causedBy(failure, CancellationException.class) || causedBy(failure, RejectedExecutionException.class)
                || causedBy(failure, DataAccessException.class);
    }

    private boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private int slot(Kind kind, Lane lane) {
        return kind.ordinal() * Lane.values().length + lane.ordinal();
    }

    private String topic(Kind kind, Lane lane) {
        return kind == Kind.INITIAL ? lane == Lane.HEIC ? topics.initialHeic() : topics.initialLight()
                : lane == Lane.HEIC ? topics.additionalHeic() : topics.additionalLight();
    }

    @PreDestroy
    void close() {
        try {
            drain().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            log.warn("이미지 변환 종료 대기 실패: cause={}", failure.getClass().getSimpleName());
        }
    }

    private enum Stage {
        CLAIM, BEGIN, PHOTO, SAVE, FAIL, FINISH, RELEASE
    }

    private static final class Job {
        private final String id;
        private final Kind kind;
        private final Lane lane;
        private final String topic;
        private final int partition;
        private final long order;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
        private Stage stage = Stage.CLAIM;
        private AttachmentConversionClaim claim;
        private int index;
        private long readyAt;
        private boolean busy;
        private boolean revoked;
        private volatile boolean revokeRequested;
        private volatile boolean fenced;
        private DerivedPhotoKeys output;
        private Throwable failure;
        private ScheduledFuture<?> retry;
        private ScheduledFuture<?> heartbeat;

        private Job(String id, Kind kind, Lane lane, String topic, int partition, long order) {
            this.id = id;
            this.kind = kind;
            this.lane = lane;
            this.topic = topic;
            this.partition = partition;
            this.order = order;
            this.readyAt = System.nanoTime();
        }

        private Photo photo() {
            return claim.photos().get(index);
        }
    }
}
