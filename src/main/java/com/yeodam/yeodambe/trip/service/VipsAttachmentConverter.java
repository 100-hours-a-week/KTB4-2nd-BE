package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class VipsAttachmentConverter {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("photo-native-deadline").daemon(true).factory());

    static {
        NativeImageLibraries.initialize();
        Vips.disableOperationCache();
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private VipsAttachmentConverter() {
    }

    static void convert(Path original, String mimeType, Path analyze, Path preview, Path display, int analyzeQuality) {
        Vips.run(arena -> {
            try (ConversionDeadline deadline = new ConversionDeadline(Duration.ofSeconds(120))) {
                try {
                    boolean heic = "image/heic".equals(mimeType);
                    VImage thumbnail;
                    if (heic) {
                        HeifAttachmentDecoder.Decoded decoded = HeifAttachmentDecoder.decode(arena, original);
                        VImage pixels = decoded.pixels();
                        deadline.watch(pixels);
                        saveDisplay(pixels, display, decoded.profile());
                        thumbnail = pixels.thumbnailImage(1024,
                                VipsOption.Enum("size", VipsSize.SIZE_DOWN),
                                VipsOption.Boolean("no-rotate", true));
                    } else {
                        VImage input = switch (mimeType) {
                            case "image/jpeg" -> VImage.jpegload(arena, original.toString());
                            case "image/png" -> VImage.pngload(arena, original.toString());
                            default -> throw new IllegalArgumentException(
                                    TripInternalErrorMessage.PHOTO_FORMAT_UNSUPPORTED.message());
                        };
                        String expected = "image/jpeg".equals(mimeType) ? "jpegload" : "pngload";
                        if (!expected.equals(input.getString("vips-loader"))
                                || input.getWidth() < 1 || input.getHeight() < 1) {
                            throw new IllegalArgumentException(TripInternalErrorMessage.PHOTO_FORMAT_OR_DIMENSIONS_INVALID.message());
                        }
                        thumbnail = VImage.thumbnail(arena, original.toString(), 1024,
                                VipsOption.Enum("size", VipsSize.SIZE_DOWN));
                    }
                    deadline.watch(thumbnail);
                    thumbnail = thumbnail.copyMemory();
                    VImage analyzePixels = thumbnail.hasAlpha()
                            ? thumbnail.flatten(VipsOption.ArrayDouble("background", List.of(255.0, 255.0, 255.0)))
                            : thumbnail;
                    deadline.watch(analyzePixels);
                    analyzePixels.jpegsave(analyze.toString(), VipsOption.Int("Q", analyzeQuality),
                            VipsOption.Boolean("strip", true));
                    deadline.watch(thumbnail);
                    thumbnail.webpsave(preview.toString(), VipsOption.Int("Q", 75),
                            VipsOption.Int("effort", 4), VipsOption.Boolean("strip", true));
                    deadline.check();
                } catch (RuntimeException failure) {
                    deadline.check();
                    throw failure;
                }
            }
        });
    }

    static final class ConversionDeadline implements AutoCloseable {
        private final ScheduledFuture<?> timer;
        private MemorySegment image;
        private boolean expired;

        ConversionDeadline(Duration timeout) {
            timer = DEADLINES.schedule(this::expire, timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        synchronized void watch(VImage current) {
            check();
            // kill만 다른 스레드에서 호출한다. close와 같은 잠금으로 Arena 해제 뒤 접근을 막는다.
            image = MemorySegment.ofAddress(current.getUnsafeStructAddress().address());
        }

        synchronized void check() {
            if (expired) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.PHOTO_CONVERSION_TIMED_OUT.message(), new TimeoutException());
            }
        }

        private synchronized void expire() {
            expired = true;
            if (image != null) {
                VipsHelper.image_set_kill(image, true);
            }
        }

        @Override
        public synchronized void close() {
            image = null;
            timer.cancel(false);
            check();
        }
    }

    private static void saveDisplay(VImage pixels, Path display, byte[] profile) {
        if (profile == null) {
            pixels.jpegsave(display.toString(), VipsOption.Int("Q", 95),
                    VipsOption.Boolean("strip", true));
            return;
        }
        Path profileFile = display.resolveSibling("display.icc");
        try {
            Files.write(profileFile, profile);
            pixels.jpegsave(display.toString(), VipsOption.Int("Q", 95),
                    VipsOption.Boolean("strip", true), VipsOption.String("profile", profileFile.toString()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            try {
                Files.deleteIfExists(profileFile);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
