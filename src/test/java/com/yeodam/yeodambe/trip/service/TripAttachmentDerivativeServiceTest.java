package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @BeforeEach
    void setUp() {
        storage = mock(TripAttachmentStorageClient.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new TripAttachmentDerivativeService(storage, new ObjectMapper(), meterRegistry);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void 원본에서_분석용_JPEG와_미리보기_WebP를_생성하고_임시파일을_삭제한다() throws Exception {
        AtomicReference<byte[]> analyzeBytes = new AtomicReference<>();
        AtomicReference<byte[]> previewBytes = new AtomicReference<>();
        AtomicReference<Path> analyzePath = new AtomicReference<>();
        AtomicReference<Path> previewPath = new AtomicReference<>();

        when(storage.open("original/key")).thenReturn(new ByteArrayInputStream(jpeg()));
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

    @Test
    void Worker에_요청_ID를_전달하고_작업_종료_후_비운다() {
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
                        "run-1", List.of("original/key"), List.of("image/jpeg")).join());

        org.slf4j.MDC.clear();
        assertThrows(CompletionException.class,
                () -> service.createAll(
                        "run-2", List.of("original/key"), List.of("image/jpeg")).join());

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
            assertEquals("icc", command("identify", "-format", "%[profiles]", output.toString()));
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
