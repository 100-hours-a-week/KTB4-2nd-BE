package com.yeodam.yeodambe.trip.service;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VipsAttachmentConverterTest {
    @BeforeAll
    static void initializeNativeLibraries() {
        NativeImageLibraries.initialize();
    }

    @Test
    void 회전을_생략한_HEIC_픽셀에도_원본_ICC_프로필을_전달한다() {
        Vips.run(arena -> {
            var source = Path.of("src/test/resources/images/heic/oriented-with-exif.heic");
            byte[] expected = VImage.heifload(arena, source.toString()).getBlob("icc-profile-data").getBytes();
            var decoded = HeifUntransformedDecoder.decode(arena, source);
            assertArrayEquals(expected, decoded.pixels().getBlob("icc-profile-data").getBytes());
        });
    }

    @Test
    void 시간초과는_다른_스레드에서_중단을_요청하고_재시도_가능한_원인을_유지한다() {
        Vips.run(arena -> {
            VImage image = VImage.newFromFile(arena, "src/test/resources/images/heic/oriented-with-exif.heic");
            VipsAttachmentConverter.ConversionDeadline deadline =
                    new VipsAttachmentConverter.ConversionDeadline(Duration.ofMinutes(2));
            deadline.watch(image);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread watchdog = Thread.ofPlatform().start(() -> {
                try {
                    ReflectionTestUtils.invokeMethod(deadline, "expire");
                } catch (Throwable failure) {
                    error.set(failure);
                }
            });
            try {
                watchdog.join(5000);
                assertNull(error.get());
                IllegalStateException failure = assertThrows(IllegalStateException.class, deadline::check);
                assertInstanceOf(TimeoutException.class, failure.getCause());
                assertThrows(IllegalStateException.class, deadline::close);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } finally {
                try {
                    deadline.close();
                } catch (IllegalStateException failure) {
                    assertInstanceOf(TimeoutException.class, failure.getCause());
                }
            }
        });
    }
}
