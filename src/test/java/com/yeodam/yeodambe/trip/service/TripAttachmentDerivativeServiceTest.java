package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentDerivativeServiceTest {
    private TripAttachmentStorageClient storage;
    private TripAttachmentDerivativeService service;
    private SimpleMeterRegistry meterRegistry;
    private TripDerivativeScheduler scheduler;

    @BeforeEach
    void setUp() {
        storage = mock(TripAttachmentStorageClient.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new TripDerivativeScheduler(1, 1, 10, 20, meterRegistry);
        service = new TripAttachmentDerivativeService(storage, new ObjectMapper(), meterRegistry, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void 원본에서_분석용_JPEG와_미리보기_WebP를_생성하고_임시파일을_삭제한다() throws Exception {
        AtomicReference<byte[]> analyzeBytes = new AtomicReference<>();
        AtomicReference<byte[]> previewBytes = new AtomicReference<>();
        AtomicReference<Path> analyzePath = new AtomicReference<>();
        AtomicReference<Path> previewPath = new AtomicReference<>();

        byte[] originalBytes = jpeg();
        when(storage.open("original/key")).thenReturn(new ByteArrayInputStream(originalBytes));
        when(storage.storeDerived(eq("run-1"), any(Path.class), eq("image/jpeg")))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    analyzeBytes.set(Files.readAllBytes(path));
                    analyzePath.set(path);
                    return "derived/analyze.jpg";
                });
        when(storage.storeDerived(eq("run-1"), any(Path.class), eq("image/webp")))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    previewBytes.set(Files.readAllBytes(path));
                    previewPath.set(path);
                    return "derived/preview.webp";
                });

        List<DerivedPhotoKeys> result = service.createAll(
                "run-1", List.of("original/key"), List.of("image/jpeg")).join();

        assertEquals("original/key", result.getFirst().originalKey());
        assertEquals("derived/analyze.jpg", result.getFirst().analyzeKey());
        assertEquals("derived/preview.webp", result.getFirst().previewKey());
        assertEquals(Long.valueOf(originalBytes.length), result.getFirst().originalSizeBytes());
        assertEquals(Long.valueOf(analyzeBytes.get().length), result.getFirst().analyzeSizeBytes());
        assertEquals(Long.valueOf(previewBytes.get().length), result.getFirst().previewSizeBytes());
        assertNull(result.getFirst().displaySizeBytes());
        verify(storage, never()).size(any(String.class));
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8},
                Arrays.copyOf(analyzeBytes.get(), 2));
        assertEquals("RIFF", new String(previewBytes.get(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(previewBytes.get(), 8, 4, StandardCharsets.US_ASCII));
        assertFalse(Files.exists(analyzePath.get()));
        assertFalse(Files.exists(previewPath.get()));
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "original_s3_read", "outcome", "success")
                .timer()
                .count());
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "image_derivative", "outcome", "success")
                .timer()
                .count());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "2010:01:01 12:44:33||2010-01-01T12:44:33+09:00",
            "2010:01:01 12:44:33|+05:30|2010-01-01T12:44:33+05:30",
            "|+09:00|",
            "2010:13:01 12:44:33||",
            "2010:01:01 12:44:33|invalid|"
    }, delimiter = '|', nullValues = "")
    void 촬영시각의_누락된_시간대만_한국_시간으로_해석한다(
            String date, String offset, String expected
    ) throws Exception {
        Path original = Files.createTempFile("camera-metadata", ".jpg");
        try {
            Files.write(original, jpeg());
            command("exiftool", "-n", "-m", "-overwrite_original",
                    "-DateTimeOriginal=" + (date == null ? "" : date),
                    "-OffsetTimeOriginal=" + (offset == null ? "" : offset),
                    "-Make=SAMSUNG", "-Model=NX100", original.toString());
            ObjectMapper json = new ObjectMapper();
            var sourceMetadata = json.readTree(command("exiftool", "-j", "-n",
                    "-DateTimeOriginal", "-OffsetTimeOriginal", original.toString())).get(0);
            if (date != null) assertEquals(date, sourceMetadata.path("DateTimeOriginal").asString());
            if (offset != null) assertEquals(offset, sourceMetadata.path("OffsetTimeOriginal").asString());
            byte[] source = Files.readAllBytes(original);
            when(storage.open("original/camera")).thenReturn(new ByteArrayInputStream(source));
            when(storage.storeDerived(eq("run-camera"), any(Path.class), any(String.class)))
                    .thenAnswer(invocation -> {
                        Path output = invocation.getArgument(1);
                        if (output.getFileName().toString().equals("analyze.jpg")) {
                            var metadata = json.readTree(command("exiftool", "-j", "-n",
                                    "-DateTimeOriginal", "-OffsetTimeOriginal", "-GPSLatitude",
                                    "-GPSLongitude", output.toString())).get(0);
                            if (expected != null || date == null) {
                                assertEquals(sourceMetadata.get("DateTimeOriginal"), metadata.get("DateTimeOriginal"));
                                assertEquals(sourceMetadata.get("OffsetTimeOriginal"), metadata.get("OffsetTimeOriginal"));
                            }
                            assertFalse(metadata.has("GPSLatitude"));
                            assertFalse(metadata.has("GPSLongitude"));
                        }
                        return "derived/" + output.getFileName();
                    });

            DerivedPhotoKeys result = service.createAll("run-camera",
                    List.of("original/camera"), List.of("image/jpeg")).join().getFirst();

            assertEquals(expected == null ? null : OffsetDateTime.parse(expected), result.takenAt());
            assertNull(result.latitude());
            assertNull(result.longitude());
            assertEquals("SAMSUNG NX100", result.deviceModel());
            assertArrayEquals(source, Files.readAllBytes(original));
        } finally {
            Files.deleteIfExists(original);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image/jpeg", "image/heic"})
    void Worker에_요청_ID를_전달하고_작업_종료_후_비운다(String mime) {
        AtomicReference<String> firstRequestId = new AtomicReference<>();
        AtomicReference<String> secondRequestId = new AtomicReference<>();

        when(storage.open("original/key"))
                .thenAnswer(invocation -> {
                    if (firstRequestId.get() == null) {
                        firstRequestId.set(org.slf4j.MDC.get("request_id"));
                    } else {
                        secondRequestId.set(org.slf4j.MDC.get("request_id"));
                    }
                    throw new IllegalStateException("테스트에서 작업을 중단합니다.");
                });

        org.slf4j.MDC.put("request_id", "request-789");
        assertThrows(CompletionException.class,
                () -> service.createAll(
                        "run-1", List.of("original/key"), List.of(mime)).join());

        org.slf4j.MDC.clear();
        assertThrows(CompletionException.class,
                () -> service.createAll(
                        "run-2", List.of("original/key"), List.of(mime)).join());

        assertEquals("request-789", firstRequestId.get());
        assertEquals(null, secondRequestId.get());
    }

    @Test
    void HEIC는_원본_해상도_JPEG_표시본을_생성하고_EXIF를_제거한다() throws Exception {
        AtomicReference<byte[]> displayBytes = new AtomicReference<>();
        AtomicReference<byte[]> analyzeBytes = new AtomicReference<>();
        AtomicReference<byte[]> previewBytes = new AtomicReference<>();
        byte[] heic = Files.readAllBytes(Path.of(
                "src/test/resources/images/heic/oriented-with-exif.heic"));
        when(storage.open("original/heic")).thenReturn(new ByteArrayInputStream(heic));
        when(storage.storeDerived(eq("run-heic"), any(Path.class), any(String.class)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    if (path.getFileName().toString().equals("display.jpg")) {
                        displayBytes.set(Files.readAllBytes(path));
                        return "derived/display.jpg";
                    }
                    if (path.getFileName().toString().equals("analyze.jpg")) {
                        analyzeBytes.set(Files.readAllBytes(path));
                        return "derived/analyze.jpg";
                    }
                    previewBytes.set(Files.readAllBytes(path));
                    return "derived/preview.webp";
                });

        DerivedPhotoKeys result = service.createAll(
                "run-heic", List.of("original/heic"), List.of("image/heic")).join().getFirst();

        assertEquals("derived/display.jpg", result.displayKey());
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8},
                Arrays.copyOf(displayBytes.get(), 2));
        BufferedImage display = ImageIO.read(new ByteArrayInputStream(displayBytes.get()));
        assertEquals(3, display.getWidth());
        assertEquals(2, display.getHeight());
        BufferedImage analyze = ImageIO.read(new ByteArrayInputStream(analyzeBytes.get()));
        assertEquals(3, analyze.getWidth());
        assertEquals(2, analyze.getHeight());

        Path output = Files.createTempFile("heic-display", ".jpg");
        Path previewOutput = Files.createTempFile("heic-preview", ".webp");
        try {
            Files.write(output, displayBytes.get());
            Files.write(previewOutput, previewBytes.get());
            assertEquals("3x2", command("identify", "-format", "%wx%h", previewOutput.toString()));
            assertEquals("95", command("identify", "-format", "%Q", output.toString()));
            var metadata = new ObjectMapper().readTree(command(
                    "exiftool", "-j", "-Orientation", "-GPSLatitude", "-GPSLongitude",
                    "-DateTimeOriginal", "-Make", "-Model", output.toString())).get(0);
            assertFalse(metadata.has("Orientation"));
            assertFalse(metadata.has("GPSLatitude"));
            assertFalse(metadata.has("GPSLongitude"));
            assertFalse(metadata.has("DateTimeOriginal"));
            assertFalse(metadata.has("Make"));
            assertFalse(metadata.has("Model"));
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(previewOutput);
        }
    }

    @Test
    void JPEG는_표시본을_생성하지_않는다() {
        when(storage.open("original/jpeg")).thenReturn(new ByteArrayInputStream(uncheckedJpeg()));
        when(storage.storeDerived(eq("run-jpeg"), any(Path.class), eq("image/jpeg")))
                .thenReturn("derived/analyze.jpg");
        when(storage.storeDerived(eq("run-jpeg"), any(Path.class), eq("image/webp")))
                .thenReturn("derived/preview.webp");

        DerivedPhotoKeys result = service.createAll(
                "run-jpeg", List.of("original/jpeg"), List.of("image/jpeg")).join().getFirst();

        assertNull(result.displayKey());
        verify(storage, never()).storeDerived(eq("run-jpeg"),
                org.mockito.ArgumentMatchers.argThat(path -> path.getFileName().toString().equals("display.jpg")),
                eq("image/jpeg"));
    }

    @Test
    void 원본키와_MIME_개수가_다르면_생성을_시작하지_않는다() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createAll("run-mismatch", List.of("one"), List.of()).join());

        verifyNoInteractions(storage);
    }

    @Test
    void 표시본_저장에_실패하면_앞서_저장한_파생객체도_삭제한다() throws Exception {
        byte[] heic = Files.readAllBytes(Path.of(
                "src/test/resources/images/heic/oriented-with-exif.heic"));
        when(storage.open("original/heic")).thenReturn(new ByteArrayInputStream(heic));
        when(storage.storeDerived(eq("run-fail"), any(Path.class), any(String.class)))
                .thenAnswer(invocation -> switch (((Path) invocation.getArgument(1)).getFileName().toString()) {
                    case "analyze.jpg" -> "derived/analyze.jpg";
                    case "preview.webp" -> "derived/preview.webp";
                    default -> throw new AttachmentStorageException(
                            "derived/display-failed.jpg", new RuntimeException("S3 failure"));
                });

        assertThrows(RuntimeException.class, () -> service.createAll(
                "run-fail", List.of("original/heic"), List.of("image/heic")).join());

        verify(storage).delete("derived/analyze.jpg");
        verify(storage).delete("derived/preview.webp");
        verify(storage).delete("derived/display-failed.jpg");
        assertEquals(1, meterRegistry.find("yeodam.trip.stage")
                .tags("stage", "image_derivative", "outcome", "failure")
                .timer()
                .count());
    }

    @ParameterizedTest
    @CsvSource({"1, RGBY", "2, GRYB", "3, YBGR", "4, BYRG",
            "5, RBGY", "6, BRYG", "7, YGBR", "8, GYRB"})
    void JPEG와_PNG의_회전과_반전을_두_출력에_적용한다(int orientation, String corners) throws Exception {
        for (String format : List.of("jpeg", "png")) {
            Path source = Files.createTempFile("oriented-source", "." + format);
            try {
                BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
                Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
                for (int y = 0; y < 40; y++) {
                    for (int x = 0; x < 80; x++) {
                        image.setRGB(x, y, colors[(y / 20) * 2 + x / 40].getRGB());
                    }
                }
                ImageIO.write(image, format, source.toFile());
                command("exiftool", "-overwrite_original", "-Orientation#=" + orientation, source.toString());
                Map<String, byte[]> outputs = deriveOutputs(Files.readAllBytes(source), "image/" + format);
                assertEquals(2, outputs.size());
                for (String name : List.of("analyze.jpg", "preview.webp")) {
                    BufferedImage output = decodeOutput(outputs.get(name), name);
                    assertEquals(orientation <= 4 ? 80 : 40, output.getWidth());
                    assertEquals(orientation <= 4 ? 40 : 80, output.getHeight());
                    for (int i = 0; i < 4; i++) {
                        int x = output.getWidth() * (i % 2 == 0 ? 1 : 3) / 4;
                        int y = output.getHeight() * (i < 2 ? 1 : 3) / 4;
                        Color expected = switch (corners.charAt(i)) {
                            case 'R' -> Color.RED;
                            case 'G' -> Color.GREEN;
                            case 'B' -> Color.BLUE;
                            default -> Color.YELLOW;
                        };
                        Color actual = new Color(output.getRGB(x, y));
                        assertTrue(Math.abs(expected.getRed() - actual.getRed()) < 50);
                        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) < 50);
                        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) < 50);
                    }
                }
            } finally {
                Files.deleteIfExists(source);
            }
        }
    }

    @Test
    void 공통_축소_후_분석본의_흰_배경이_WebP_투명도에_영향을_주지_않는다() throws Exception {
        BufferedImage input = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_ARGB);
        input.setRGB(100, 100, Color.RED.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(input, "png", bytes);
        Map<String, byte[]> outputs = deriveOutputs(bytes.toByteArray(), "image/png");
        BufferedImage analyze = decodeOutput(outputs.get("analyze.jpg"), "analyze.jpg");
        BufferedImage preview = decodeOutput(outputs.get("preview.webp"), "preview.webp");
        assertEquals(1024, analyze.getWidth());
        assertEquals(512, analyze.getHeight());
        assertEquals(1024, preview.getWidth());
        assertEquals(512, preview.getHeight());
        assertEquals(Color.WHITE.getRGB(), analyze.getRGB(500, 300));
        assertEquals(0, preview.getRGB(500, 300) >>> 24);
    }

    @Test
    void 분석본에_선택한_EXIF와_정상_방향을_기록하고_미리보기에서는_제거한다() throws Exception {
        Path source = Files.createTempFile("selected-exif", ".jpg");
        Path output = Files.createTempFile("selected-exif-output", ".jpg");
        try {
            Files.write(source, jpeg());
            command("exiftool", "-overwrite_original", "-Orientation#=6",
                    "-DateTimeOriginal=2026:10:02 10:00:00", "-OffsetTimeOriginal=+09:00",
                    "-GPSLatitude=37.5", "-GPSLatitudeRef=N", "-GPSLongitude=127", "-GPSLongitudeRef=E",
                    "-Make=SAMSUNG", "-Model=NX100", source.toString());
            byte[] original = Files.readAllBytes(source);
            Map<String, byte[]> outputs = deriveOutputs(original, "image/jpeg");
            for (String name : List.of("analyze.jpg", "preview.webp")) {
                Files.write(output, outputs.get(name));
                var metadata = new ObjectMapper().readTree(command("exiftool", "-j", "-n",
                        "-Orientation", "-DateTimeOriginal", "-GPSLatitude", "-GPSLongitude",
                        "-Make", "-Model", output.toString())).get(0);
                if (name.equals("analyze.jpg")) {
                    assertEquals(1, metadata.path("Orientation").asInt());
                    assertEquals("2026:10:02 10:00:00", metadata.path("DateTimeOriginal").asString());
                    assertEquals(37.5, metadata.path("GPSLatitude").asDouble());
                    assertEquals(127, metadata.path("GPSLongitude").asDouble());
                    assertEquals("SAMSUNG", metadata.path("Make").asString());
                    assertEquals("NX100", metadata.path("Model").asString());
                } else {
                    for (String field : List.of("Orientation", "DateTimeOriginal", "GPSLatitude",
                            "GPSLongitude", "Make", "Model")) assertFalse(metadata.has(field));
                }
            }
            assertArrayEquals(original, Files.readAllBytes(source));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void 배치_뒤_사진이_손상되면_앞_사진의_파생객체를_정리한다() throws Exception {
        when(storage.open("original/good")).thenReturn(new ByteArrayInputStream(jpeg()));
        when(storage.open("original/bad")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        AtomicReference<Path> directory = new AtomicReference<>();
        when(storage.storeDerived(eq("run-batch-fail"), any(Path.class), any(String.class)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    directory.set(path.getParent());
                    return "derived/" + path.getFileName();
                });
        assertThrows(CompletionException.class, () -> service.createAll("run-batch-fail",
                List.of("original/good", "original/bad"), List.of("image/jpeg", "image/jpeg")).join());
        verify(storage).delete("derived/analyze.jpg");
        verify(storage).delete("derived/preview.webp");
        assertFalse(Files.exists(directory.get()));
        verify(storage, org.mockito.Mockito.times(2)).storeDerived(eq("run-batch-fail"), any(Path.class), any(String.class));
    }

    @Test
    void 다른_워커_실패_뒤_늦은_PUT과_실패키까지_정리한_후_실패한다() throws Exception {
        var putStarted = new java.util.concurrent.CountDownLatch(1);
        var heavyFailed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(storage.open("light")).thenReturn(new ByteArrayInputStream(jpeg()));
        when(storage.open("heavy")).thenAnswer(invocation -> {
            assertTrue(putStarted.await(5, TimeUnit.SECONDS));
            heavyFailed.countDown();
            throw new IllegalStateException("HEIC GET 실패");
        });
        when(storage.storeDerived(eq("mixed-failure"), any(Path.class), eq("image/jpeg"))).thenAnswer(invocation -> {
            putStarted.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "late/analyze";
        });
        when(storage.storeDerived(eq("mixed-failure"), any(Path.class), eq("image/webp")))
                .thenThrow(new AttachmentStorageException("late/failed-preview", new IllegalStateException("PUT")));
        try {
            var future = service.createAll("mixed-failure", List.of("light", "heavy"), List.of("image/jpeg", "image/heic"));
            assertTrue(heavyFailed.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            verify(storage, never()).delete(any());
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            verify(storage).delete("late/analyze");
            verify(storage).delete("late/failed-preview");
            verify(storage, never()).delete("other-batch/key");
        } finally { release.countDown(); }
    }

    @Test
    void 외부_Future가_취소돼도_실행중_PUT_정착_후_파일을_보상한다() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cleaned = new java.util.concurrent.CountDownLatch(2);
        when(storage.open("cancel-original")).thenReturn(new ByteArrayInputStream(jpeg()));
        when(storage.storeDerived(eq("cancel-run"), any(Path.class), any(String.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "cancel/" + ((Path) invocation.getArgument(1)).getFileName();
        });
        org.mockito.Mockito.doAnswer(invocation -> { cleaned.countDown(); return null; }).when(storage).delete(any());
        try {
            var future = service.createAll("cancel-run", List.of("cancel-original"), List.of("image/jpeg"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            verify(storage, never()).delete(any());
            release.countDown();
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
            verify(storage).delete("cancel/analyze.jpg");
            verify(storage).delete("cancel/preview.webp");
        } finally { release.countDown(); }
    }

    @Test
    void 혼합_성공은_원본_순서와_필수키를_유지하고_같은_execution의_다른_배치를_삭제하지_않는다() throws Exception {
        byte[] heic = Files.readAllBytes(Path.of("src/test/resources/images/heic/oriented-with-exif.heic"));
        when(storage.open("mix-heavy")).thenReturn(new ByteArrayInputStream(heic));
        when(storage.open("mix-light")).thenReturn(new ByteArrayInputStream(jpeg()));
        when(storage.open("other-bad")).thenThrow(new IllegalStateException("GET"));
        when(storage.storeDerived(eq("same-run"), any(Path.class), any(String.class))).thenAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            return path.getParent().getFileName() + "/" + path.getFileName();
        });
        var success = service.createAll("same-run", List.of("mix-heavy", "mix-light"), List.of("image/heic", "image/jpeg"));
        var failed = service.createAll("same-run", List.of("other-bad"), List.of("image/png"));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
        var photos = success.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("mix-heavy", "mix-light"), photos.stream().map(DerivedPhotoKeys::originalKey).toList());
        assertTrue(photos.getFirst().displayKey().endsWith("display.jpg"));
        assertNull(photos.getLast().displayKey());
        for (var photo : photos) {
            assertTrue(photo.analyzeKey().endsWith("analyze.jpg"));
            assertTrue(photo.previewKey().endsWith("preview.webp"));
        }
        verify(storage, never()).delete(any());
    }

    @Test
    void interrupt된_변환_프로세스가_종료된_후에만_호출이_끝난다() throws Exception {
        Path pidFile = Files.createTempFile("derivative-process", ".pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "run", (Object) new String[]{
                        "/bin/sh", "-c", "echo $$ > '" + pidFile + "'; exec sleep 30"});
            } catch (Throwable cause) { failure.set(cause); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.setDaemon(true);
        ProcessHandle process = null;
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.size(pidFile) == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(Files.size(pidFile) > 0);
            process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
            worker.interrupt();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof IllegalStateException);
            assertTrue(interrupted.get());
            assertFalse(process.isAlive(), "Future 정착 이후 자식 프로세스가 남으면 안 됩니다.");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            worker.interrupt();
            worker.join(5000);
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void EXIF_출력_EOF_대기중에도_interrupt로_프로세스를_종료한다() throws Exception {
        Path directory = Files.createTempDirectory("blocked-exif");
        Path source = directory.resolve("input.jpg");
        assertEquals(0, new ProcessBuilder("mkfifo", source.toString()).start().waitFor());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "metadata", source); }
            catch (Throwable cause) { failure.set(cause); }
        });
        worker.setDaemon(true);
        ProcessHandle process = null;
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process == null && System.nanoTime() < deadline) {
                process = ProcessHandle.current().descendants()
                        .filter(child -> child.info().commandLine().orElse("").contains(source.toString()))
                        .findFirst().orElse(null);
                Thread.onSpinWait();
            }
            assertTrue(process != null, "exiftool 프로세스가 시작되어야 합니다.");
            worker.interrupt();
            worker.join(2000);
            assertFalse(worker.isAlive(), "출력 readAllBytes는 interrupt를 막으면 안 됩니다.");
            assertTrue(failure.get() instanceof IllegalStateException);
            assertFalse(process.isAlive());
            assertFalse(Files.exists(directory.resolve("metadata.json")));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            worker.interrupt();
            worker.join(5000);
            Files.deleteIfExists(directory.resolve("metadata.json"));
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    private Map<String, byte[]> deriveOutputs(byte[] original, String mimeType) {
        Map<String, byte[]> outputs = new HashMap<>();
        AtomicReference<Path> directory = new AtomicReference<>();
        // 이 입력은 한 번만 열 수 있다. 같은 테스트에서 여러 입력을 처리할 때도 각각 새 stream을 제공한다.
        when(storage.open("original/test")).thenReturn(new ByteArrayInputStream(original));
        when(storage.storeDerived(eq("run-output"), any(Path.class), any(String.class)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    outputs.put(path.getFileName().toString(), Files.readAllBytes(path));
                    directory.set(path.getParent());
                    return "derived/" + path.getFileName();
                });
        DerivedPhotoKeys result = service.createAll("run-output",
                List.of("original/test"), List.of(mimeType)).join().getFirst();
        if (!mimeType.equals("image/heic")) assertNull(result.displayKey());
        assertFalse(Files.exists(directory.get()));
        return outputs;
    }

    private static BufferedImage decodeOutput(byte[] bytes, String name) throws Exception {
        if (!name.endsWith(".webp")) return ImageIO.read(new ByteArrayInputStream(bytes));
        Path source = Files.createTempFile("preview-decode", ".webp");
        Path decoded = Files.createTempFile("preview-decode", ".png");
        try {
            Files.write(source, bytes);
            command("convert", source.toString(), decoded.toString());
            return ImageIO.read(decoded.toFile());
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(decoded);
        }
    }

    private static String command(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException(output);
        }
        return output.trim();
    }

    private static byte[] uncheckedJpeg() {
        try {
            return jpeg();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
