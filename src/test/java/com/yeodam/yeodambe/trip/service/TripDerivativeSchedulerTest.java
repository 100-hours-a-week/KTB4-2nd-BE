package com.yeodam.yeodambe.trip.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TripDerivativeSchedulerTest {
    private final io.micrometer.core.instrument.MockClock clock = new io.micrometer.core.instrument.MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(io.micrometer.core.instrument.simple.SimpleConfig.DEFAULT, clock);
    private final TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 10, 20, meters);

    @AfterEach
    void stop() { scheduler.stop(); }

    @Test
    void HEIC가_실행중이어도_JPEG는_별도_워커에서_완료한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var heavy = scheduler.submit("heavy", List.of(task(0, "image/heic", () -> {
                started.countDown();
                await(release);
                return keys("heavy");
            })), () -> true);
            await(started);
            var light = scheduler.submit("light", List.of(task(0, "image/jpeg", () -> keys("light"))), () -> true);
            assertEquals("light", light.get(5, TimeUnit.SECONDS).getFirst().originalKey());
            assertFalse(heavy.isDone());
            release.countDown();
            assertEquals("heavy", heavy.get(5, TimeUnit.SECONDS).getFirst().originalKey());
        } finally { release.countDown(); }
    }

    @Test
    void 같은_포맷의_다른_배치가_큰_배치의_다음_사진보다_먼저_실행된다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            var a = scheduler.submit("same-id", List.of(
                    task(0, "image/heic", () -> { order.add("A1"); started.countDown(); await(release); return keys("A1"); }),
                    task(1, "image/heic", () -> { order.add("A2"); return keys("A2"); })), () -> true);
            await(started);
            var b = scheduler.submit("same-id", List.of(task(0, "image/heic", () -> { order.add("B1"); return keys("B1"); })), () -> true);
            release.countDown();
            assertEquals(List.of("A1", "A2"), a.get(5, TimeUnit.SECONDS).stream().map(DerivedPhotoKeys::originalKey).toList());
            assertEquals("B1", b.get(5, TimeUnit.SECONDS).getFirst().originalKey());
            assertEquals(List.of("A1", "B1", "A2"), order);
        } finally { release.countDown(); }
    }

    @Test
    void 혼합_배치의_완료_순서와_무관하게_index_순서로_반환한다() throws Exception {
        var lightDone = new CountDownLatch(1);
        var future = scheduler.submit("mixed", List.of(
                task(1, "image/png", () -> { lightDone.countDown(); return keys("second"); }),
                task(0, "image/heic", () -> { await(lightDone); return keys("first"); })), () -> true);
        assertEquals(List.of("first", "second"), future.get(5, TimeUnit.SECONDS).stream().map(DerivedPhotoKeys::originalKey).toList());
    }

    @Test
    void 실행중과_미제출_사진을_모두_한도에_포함하고_혼합_접수를_원자적으로_거부한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try {
            var full = scheduler.submit("full", java.util.stream.IntStream.range(0, 10).mapToObj(i ->
                    task(i, "image/heic", () -> { started.countDown(); await(release); return keys("full"); })).toList(), () -> true);
            await(started);
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("mixed", List.of(
                    task(0, "image/heic", () -> { calls.incrementAndGet(); return keys("heic"); }),
                    task(1, "image/jpeg", () -> { calls.incrementAndGet(); return keys("jpeg"); })), () -> true));
            assertEquals(0, calls.get());
            var light = scheduler.submit("light", java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                    task(i, "image/jpeg", () -> keys("light"))).toList(), () -> true);
            assertEquals(20, light.get(5, TimeUnit.SECONDS).size());
            release.countDown();
            full.get(5, TimeUnit.SECONDS);
            assertEquals(1, scheduler.submit("again", List.of(task(0, "image/heic", () -> keys("again"))), () -> true).get(5, TimeUnit.SECONDS).size());
        } finally { release.countDown(); }
    }

    @Test
    void 잘못된_작업은_실행전에_거부하고_빈_배치는_즉시_완료한다() throws Exception {
        assertEquals(List.of(), scheduler.submit("empty", List.of(), () -> true).get(5, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit("bad", List.of(task(0, "image/gif", () -> keys("bad"))), () -> true));
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit("bad", List.of(task(1, "image/jpeg", () -> keys("bad"))), () -> true));
        assertThrows(IllegalArgumentException.class, () -> scheduler.submit("bad", List.of(task(0, "image/jpeg", () -> keys("bad")), task(0, "image/png", () -> keys("bad"))), () -> true));
        assertThrows(NullPointerException.class, () -> scheduler.submit("bad", null, () -> true));
    }

    @Test
    void 취소는_대기와_미제출을_건너뛰고_실행중_작업_정착까지_기다린다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try {
            var batch = scheduler.submit("cancel", List.of(
                    task(0, "image/heic", () -> { started.countDown(); await(release); return keys("active"); }),
                    task(1, "image/heic", () -> { calls.incrementAndGet(); return keys("pending"); })), () -> true);
            await(started);
            var queued = scheduler.submit("cancel", List.of(task(0, "image/heic", () -> { calls.incrementAndGet(); return keys("queued"); })), () -> true);
            scheduler.cancelExecution("cancel");
            assertThrows(java.util.concurrent.CancellationException.class, () -> queued.get(5, TimeUnit.SECONDS));
            assertFalse(batch.isDone());
            release.countDown();
            assertThrows(java.util.concurrent.CancellationException.class, () -> batch.get(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void stop은_미실행을_취소하고_active를_interrupt한_뒤_신규_접수를_거부한다() throws Exception {
        var started = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var batch = scheduler.submit("stop", List.of(
                task(0, "image/heic", () -> { started.countDown(); new CountDownLatch(1).await(); return keys("active"); }),
                task(1, "image/heic", () -> { calls.incrementAndGet(); return keys("pending"); })), () -> true);
        await(started);
        scheduler.stop(10, TimeUnit.MILLISECONDS);
        assertThrows(java.util.concurrent.CancellationException.class, () -> batch.get(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> scheduler.submit("after-stop", List.of(task(0, "image/jpeg", () -> keys("late"))), () -> true));
        assertEquals(0, calls.get());
    }

    @Test
    void 미제출을_포함한_대기량과_실행량을_측정하고_한쪽_포화시_혼합예약을_전부_거부한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var full = scheduler.submit("light-full", java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                    task(i, "image/jpeg", () -> { started.countDown(); await(release); return keys("light"); })).toList(), () -> true);
            await(started);
            assertEquals(20, gauge("inflight", "light"));
            assertEquals(1, gauge("active", "light"));
            assertEquals(19, gauge("pending", "light"));
            var calls = new AtomicInteger();
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("mixed-rejected", List.of(
                    task(0, "image/heic", () -> { calls.incrementAndGet(); return keys("heavy"); }),
                    task(1, "image/png", () -> { calls.incrementAndGet(); return keys("light"); })), () -> true));
            assertEquals(0, gauge("inflight", "heic"));
            assertEquals(20, gauge("inflight", "light"));
            assertEquals(0, calls.get());
            assertEquals(1, meters.get("yeodam.trip.derivative.rejected.batches").tag("lane", "light").counter().count());
            clock.add(java.time.Duration.ofSeconds(3));
            release.countDown();
            full.get(5, TimeUnit.SECONDS);
            assertEquals(0, gauge("inflight", "light"));
            assertEquals(0, gauge("pending", "light"));
            assertEquals(0, gauge("active", "light"));
            var timer = meters.get("yeodam.trip.derivative.queue.wait").tags("lane", "light", "outcome", "success").timer();
            assertEquals(20, timer.count());
            assertEquals(57, timer.totalTime(TimeUnit.SECONDS));
            int count = meters.getMeters().size();
            scheduler.submit("repeat", List.of(task(0, "image/jpeg", () -> keys("repeat"))), () -> true).get(5, TimeUnit.SECONDS);
            assertEquals(count, meters.getMeters().size());
        } finally { release.countDown(); }
    }

    @Test
    void 취소와_실패_후_모든_예약이_반환되고_대기시간을_결과별로_기록한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var running = scheduler.submit("running", List.of(task(0, "image/heic", () -> { started.countDown(); await(release); return keys("running"); })), () -> true);
            await(started);
            var queued = scheduler.submit("cancel", List.of(task(0, "image/heic", () -> keys("never"))), () -> true);
            clock.add(java.time.Duration.ofSeconds(2));
            scheduler.cancelExecution("cancel");
            assertThrows(java.util.concurrent.CancellationException.class, () -> queued.get(5, TimeUnit.SECONDS));
            assertEquals(1, gauge("inflight", "heic"));
            assertEquals(2, meters.get("yeodam.trip.derivative.queue.wait").tags("lane", "heic", "outcome", "canceled").timer().totalTime(TimeUnit.SECONDS));
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            var failed = scheduler.submit("fail", List.of(task(0, "image/heic", () -> { throw new IllegalStateException("conversion"); })), () -> true);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
            assertEquals(1, meters.get("yeodam.trip.derivative.queue.wait").tags("lane", "heic", "outcome", "failure").timer().count());
            assertEquals(0, gauge("inflight", "heic"));
            assertEquals(0, gauge("pending", "heic"));
            assertEquals(0, gauge("active", "heic"));
        } finally { release.countDown(); }
    }

    private double gauge(String kind, String lane) {
        return meters.get("yeodam.trip.derivative." + kind + ".photos").tag("lane", lane).gauge().value();
    }

    private static TripDerivativeScheduler.PhotoTask task(int index, String mime, java.util.concurrent.Callable<DerivedPhotoKeys> action) {
        return new TripDerivativeScheduler.PhotoTask(index, mime, action);
    }
    private static DerivedPhotoKeys keys(String original) { return new DerivedPhotoKeys(original, "analyze", "preview"); }
    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
}
