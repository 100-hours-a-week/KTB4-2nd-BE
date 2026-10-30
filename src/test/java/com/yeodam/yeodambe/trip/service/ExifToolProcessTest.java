package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(20)
class ExifToolProcessTest {
    private final ExifToolProcess runner = new ExifToolProcess();

    @TempDir
    Path directory;

    @AfterEach
    void closeRunner() {
        runner.close();
    }

    @Test
    void 연속_명령은_같은_프로세스를_재사용하고_제어_표시는_출력에서_제외한다() {
        var first = runner.execute(List.of("-ver"));
        Process child = processOf(runner);
        var second = runner.execute(List.of("-ver"));

        assertEquals(0, first.exitStatus());
        assertTrue(first.output().trim().matches("\\d+\\.\\d+"));
        assertEquals(first, second);
        assertSame(child, processOf(runner));
        assertTrue(child.isAlive());
        assertFalse(second.output().contains("{status"));
        assertFalse(second.output().contains("{ready"));
    }

    @Test
    void 실제_사진의_촬영_정보를_읽고_대상_JPEG에_복사한다() throws Exception {
        Path original = directory.resolve("원본 사진.jpg");
        Path analyze = directory.resolve("분석 사진.jpg");
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(pixels, "jpg", original.toFile()));
        assertTrue(ImageIO.write(pixels, "jpg", analyze.toFile()));

        var written = runner.execute(List.of("-overwrite_original",
                "-DateTimeOriginal=2026:10:20 10:00:00", "-OffsetTimeOriginal=+09:00",
                "-GPSLatitude=37.5665", "-GPSLatitudeRef=N",
                "-GPSLongitude=126.9780", "-GPSLongitudeRef=E",
                "-Make=Yeodam", "-Model=Test Camera", "-Orientation#=6", original.toString()));
        assertEquals(0, written.exitStatus());
        Process child = processOf(runner);

        var copied = runner.execute(List.of("-overwrite_original", "-TagsFromFile", original.toString(),
                "-DateTimeOriginal", "-SubSecTimeOriginal", "-OffsetTimeOriginal", "-GPS:All", "-Make", "-Model",
                "-Orientation#=1", analyze.toString()));
        assertEquals(0, copied.exitStatus());
        var read = runner.execute(List.of("-j", "-n", "-DateTimeOriginal", "-OffsetTimeOriginal",
                "-GPSLatitude", "-GPSLongitude", "-Make", "-Model", "-Orientation", analyze.toString()));
        assertEquals(0, read.exitStatus());
        var metadata = new ObjectMapper().readTree(read.output()).get(0);
        assertEquals("2026:10:20 10:00:00", metadata.path("DateTimeOriginal").asString());
        assertEquals("+09:00", metadata.path("OffsetTimeOriginal").asString());
        assertEquals(37.5665, metadata.path("GPSLatitude").asDouble(), 0.000001);
        assertEquals(126.9780, metadata.path("GPSLongitude").asDouble(), 0.000001);
        assertEquals("Yeodam", metadata.path("Make").asString());
        assertEquals("Test Camera", metadata.path("Model").asString());
        assertEquals(1, metadata.path("Orientation").asInt());
        assertSame(child, processOf(runner));
    }

    @Test
    void 동시에_요청해도_각_요청의_출력이_섞이지_않는다() throws Exception {
        runner.execute(List.of("-ver"));
        Process child = processOf(runner);
        var start = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<ExifToolProcess.Result>>();
            for (int i = 0; i < 6; i++) {
                String token = "request-" + i;
                futures.add(callers.submit(() -> {
                    start.await();
                    return runner.execute(List.of("-echo1", token, "-ver"));
                }));
            }
            start.countDown();
            for (int i = 0; i < futures.size(); i++) {
                var result = futures.get(i).get(10, TimeUnit.SECONDS);
                assertEquals(0, result.exitStatus());
                assertEquals(List.of("request-" + i),
                        result.output().lines().filter(line -> line.startsWith("request-")).toList());
            }
        }
        assertSame(child, processOf(runner));
    }

    @Test
    void 명령이_실패해도_완료_응답을_받았다면_프로세스를_유지한다() {
        var failed = runner.execute(List.of("-j", directory.resolve("missing.jpg").toString()));
        Process child = processOf(runner);
        var next = runner.execute(List.of("-ver"));

        assertNotEquals(0, failed.exitStatus());
        assertEquals(0, next.exitStatus());
        assertSame(child, processOf(runner));
        assertTrue(child.isAlive());
    }

    @Test
    void 프로세스가_명령_사이에_종료되면_다음_호출에서_다시_시작한다() throws Exception {
        runner.execute(List.of("-ver"));
        Process previous = processOf(runner);
        previous.destroyForcibly();
        assertTrue(previous.waitFor(5, TimeUnit.SECONDS));

        var next = runner.execute(List.of("-ver"));

        assertEquals(0, next.exitStatus());
        assertNotSame(previous, processOf(runner));
        assertTrue(processOf(runner).isAlive());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bad\n-ver", "bad\r-ver", "bad\u0000argument"})
    void 잘못된_인자는_거부하고_다음_정상_명령은_실행한다(String argument) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> runner.execute(Arrays.asList("-ver", argument)));
        assertEquals(TripInternalErrorMessage.EXIF_COMMAND_ARGUMENT_INVALID.message(), failure.getMessage());
        assertNull(processOf(runner));
        assertEquals(0, runner.execute(List.of("-ver")).exitStatus());
    }

    @Test
    void 잠금_대기중_중단된_요청은_공유_프로세스를_종료하지_않는다() throws Exception {
        runner.execute(List.of("-ver"));
        Process child = processOf(runner);
        ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(runner, "commandLock");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                runner.execute(List.of("-ver"));
            } catch (Throwable cause) {
                failure.set(cause);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        lock.lock();
        try {
            caller.start();
            await(() -> lock.hasQueuedThread(caller));
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interrupted.get());
            assertTrue(child.isAlive());
            assertSame(child, processOf(runner));
        } finally {
            lock.unlock();
            caller.interrupt();
            caller.join(5000);
        }
        assertEquals(0, runner.execute(List.of("-ver")).exitStatus());
    }

    @Test
    void 실제_응답_대기중_중단되면_프로세스를_정리하고_다음_호출에서_복구한다() throws Exception {
        Path fifo = directory.resolve("waiting.jpg");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor());
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                runner.execute(List.of("-j", fifo.toString()));
            } catch (Throwable cause) {
                failure.set(cause);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        Process child = null;
        try {
            caller.start();
            await(() -> processOf(runner) != null && caller.getState() == Thread.State.TIMED_WAITING);
            child = processOf(runner);
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interrupted.get());
            assertFalse(child.isAlive());
            assertNull(processOf(runner));
            assertEquals(0, runner.execute(List.of("-ver")).exitStatus());
        } finally {
            caller.interrupt();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            caller.join(5000);
            Files.deleteIfExists(fifo);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ready1}\n", "{status1}=not-a-number\n{ready1}\n", "{status1}=0\n"})
    void 불완전한_응답은_실패로_처리하고_자원을_정리한다(String output) throws Exception {
        try (runner) {
            Process child = ExifToolProcessTestSupport.응답_설정(runner, output, Executors.newSingleThreadExecutor());
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.execute(List.of("-ver")));
            assertInstanceOf(IOException.class, failure.getCause());
            if (output.equals("{status1}=0\n")) {
                assertInstanceOf(EOFException.class, failure.getCause());
            }
            assertEquals(TripInternalErrorMessage.COMMAND_FAILED.message().formatted("exiftool"), failure.getMessage());
            assertFalse(child.isAlive());
            assertNull(processOf(runner));
            assertEquals(0, runner.execute(List.of("-ver")).exitStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 응답_시간_초과는_읽기_취소와_프로세스_정리로_이어진다() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        Future<ExifToolProcess.Result> response = mock(Future.class);
        when(executor.submit(ArgumentMatchers.<Callable<ExifToolProcess.Result>>any())).thenReturn(response);
        when(response.get(120_000L, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("controlled timeout"));
        try (runner) {
            Process child = ExifToolProcessTestSupport.응답_설정(runner, "", executor);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.execute(List.of("-ver")));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(TripInternalErrorMessage.COMMAND_TIMEOUT.message().formatted("exiftool"), failure.getMessage());
            verify(response).get(120_000L, TimeUnit.MILLISECONDS);
            verify(response).cancel(true);
            verify(executor).shutdownNow();
            assertFalse(child.isAlive());
            assertNull(processOf(runner));
            assertEquals(0, runner.execute(List.of("-ver")).exitStatus());
        }
    }

    @Test
    void 도구_실행에_실패하면_원인을_보존하고_다음_호출에서_복구한다() {
        ProcessBuilder builder = new ProcessBuilder(directory.resolve("missing-exiftool").toString());
        try (ExifToolProcess unavailable = new ExifToolProcess(builder)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> unavailable.execute(List.of("-ver")));
            assertInstanceOf(IOException.class, failure.getCause());
            assertNull(processOf(unavailable));
            builder.command("exiftool", "-stay_open", "True", "-@", "-");
            assertEquals(0, unavailable.execute(List.of("-ver")).exitStatus());
        }
    }

    @Test
    void Spring_종료시_프로세스를_종료하고_이후_실행을_거부한다() {
        Process child;
        ExifToolProcess managed;
        try (var context = new AnnotationConfigApplicationContext(ExifToolProcess.class)) {
            managed = context.getBean(ExifToolProcess.class);
            managed.execute(List.of("-ver"));
            child = processOf(managed);
            assertTrue(child.isAlive());
        }
        assertFalse(child.isAlive());
        assertThrows(IllegalStateException.class, () -> managed.execute(List.of("-ver")));
        assertNull(processOf(managed));
        assertDoesNotThrow(managed::close);
    }

    private Process processOf(ExifToolProcess value) {
        return (Process) ReflectionTestUtils.getField(value, "process");
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(condition.getAsBoolean(), "요청한 상태에 도달해야 합니다.");
    }

}
