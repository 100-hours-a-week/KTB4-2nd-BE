package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripAttachmentDerivativeServiceTest {
    private TripAttachmentStorageClient storage;
    private TripAttachmentDerivativeService service;

    @BeforeEach
    void setUp() {
        storage = mock(TripAttachmentStorageClient.class);
        service = new TripAttachmentDerivativeService(storage, new ObjectMapper());
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

        List<DerivedPhotoKeys> result = service.createAll("run-1", List.of("original/key")).join();

        assertEquals("original/key", result.getFirst().originalKey());
        assertEquals("derived/analyze.jpg", result.getFirst().analyzeKey());
        assertEquals("derived/preview.webp", result.getFirst().previewKey());
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8},
                Arrays.copyOf(analyzeBytes.get(), 2));
        assertEquals("RIFF", new String(previewBytes.get(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(previewBytes.get(), 8, 4, StandardCharsets.US_ASCII));
        assertFalse(Files.exists(analyzePath.get()));
        assertFalse(Files.exists(previewPath.get()));
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
