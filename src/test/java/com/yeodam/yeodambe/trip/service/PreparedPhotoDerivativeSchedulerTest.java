package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.response.PreparedPhotoDerivative;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedPhotoDerivativeSchedulerTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 1, 1, meters);

    @TempDir
    Path directory;

    @AfterEach
    void close() {
        scheduler.stop();
        meters.close();
    }

    @Test
    void 준비_성공은_파일_소유권을_호출자에게_넘기고_닫을_때_정리한다() throws Exception {
        PreparedPhotoDerivative prepared = prepared();

        PreparedPhotoDerivative result = scheduler.submitPrepared(
                "execution", "image/jpeg", () -> prepared, () -> true).get(5, TimeUnit.SECONDS);

        assertThat(result).isSameAs(prepared);
        assertThat(result.outputs().getFirst().file()).exists();
        assertThat(result.sizeBytes()).isEqualTo(60);
        DerivedPhotoKeys stored = result.storedKeys(List.of("stored/analyze", "stored/preview"));
        assertThat(stored.originalKey()).isEqualTo("original");
        assertThat(stored.analyzeKey()).isEqualTo("stored/analyze");
        assertThat(stored.previewKey()).isEqualTo("stored/preview");
        assertThat(stored.displayKey()).isNull();
        assertThat(stored.originalSizeBytes()).isEqualTo(10);
        assertThat(stored.analyzeSizeBytes()).isEqualTo(20);
        assertThat(stored.previewSizeBytes()).isEqualTo(30);
        result.close();
        assertThat(result.directory()).doesNotExist();
    }

    @Test
    void 실행_취소_뒤_늦게_완료한_준비_결과는_반환하지_않고_파일을_정리한다() throws Exception {
        PreparedPhotoDerivative prepared = prepared();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var result = scheduler.submitPrepared("execution", "image/jpeg", () -> {
            started.countDown();
            await(release);
            return prepared;
        }, () -> true);

        try {
            await(started);
            scheduler.cancelExecution("execution");
            assertThat(prepared.outputs().getFirst().file()).exists();
            release.countDown();

            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertThat(prepared.directory()).doesNotExist();
            assertThat(meters.get("yeodam.trip.derivative.inflight.photos")
                    .tag("lane", "light").gauge().value()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 반환_Future를_취소해도_진행_중인_준비가_끝나기_전에는_파일을_지우지_않는다() throws Exception {
        PreparedPhotoDerivative prepared = prepared();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SimpleMeterRegistry cancellationMeters = new SimpleMeterRegistry();
        TripDerivativeScheduler cancellationScheduler = new TripDerivativeScheduler(1, 1, 1, 2, cancellationMeters);
        var result = cancellationScheduler.submitPrepared("execution", "image/jpeg", () -> {
            started.countDown();
            await(release);
            return prepared;
        }, () -> true);

        try {
            await(started);
            assertThat(result.cancel(false)).isTrue();
            assertThat(prepared.outputs().getFirst().file()).exists();
            var following = cancellationScheduler.submit("following", List.of(
                    new TripDerivativeScheduler.PhotoTask(0, "image/jpeg",
                            () -> new DerivedPhotoKeys("following", "analyze", "preview"))), () -> true);
            release.countDown();
            following.get(5, TimeUnit.SECONDS);

            assertThat(prepared.directory()).doesNotExist();
        } finally {
            release.countDown();
            cancellationScheduler.stop();
            cancellationMeters.close();
        }
    }

    @Test
    void 준비_작업과_기존_변환_작업은_같은_포맷의_접수_한도를_공유한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var result = scheduler.submitPrepared("execution", "image/jpeg", () -> {
            started.countDown();
            await(release);
            return prepared();
        }, () -> true);

        try {
            await(started);
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("other", List.of(
                    new TripDerivativeScheduler.PhotoTask(0, "image/jpeg",
                            () -> new DerivedPhotoKeys("original", "analyze", "preview"))), () -> true));
            release.countDown();
            result.get(5, TimeUnit.SECONDS).close();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 비활성_실행은_준비_작업을_호출하지_않는다() {
        var result = scheduler.submitPrepared("execution", "image/jpeg", () -> {
            throw new AssertionError("취소한 실행을 변환하면 안 됩니다.");
        }, () -> false);

        assertThrows(CancellationException.class, result::join);
    }

    private PreparedPhotoDerivative prepared() throws Exception {
        Path work = Files.createTempDirectory(directory, "prepared-");
        Path analyze = Files.write(work.resolve("analyze.jpg"), new byte[20]);
        Path preview = Files.write(work.resolve("preview.webp"), new byte[30]);
        DerivedPhotoKeys photo = new DerivedPhotoKeys("original", "analyze", "preview", null,
                null, null, null, null, 10L, 20L, 30L, null);
        return new PreparedPhotoDerivative(work, photo, List.of(
                new PreparedPhotoDerivative.Output(analyze, "analyze", "image/jpeg"),
                new PreparedPhotoDerivative.Output(preview, "preview", "image/webp")));
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
