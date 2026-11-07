package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.service.DerivedPhotoKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public record PreparedPhotoDerivative(
        Path directory,
        DerivedPhotoKeys photo,
        List<Output> outputs
) implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PreparedPhotoDerivative.class);

    public record Output(
            Path file,
            String objectKey,
            String mimeType
    ) {
    }

    public PreparedPhotoDerivative {
        outputs = List.copyOf(outputs);
    }

    public long sizeBytes() {
        return photo.originalSizeBytes() + photo.analyzeSizeBytes() + photo.previewSizeBytes()
                + (photo.displaySizeBytes() == null ? 0 : photo.displaySizeBytes());
    }

    public DerivedPhotoKeys storedKeys(List<String> keys) {
        return new DerivedPhotoKeys(photo.originalKey(), keys.get(0), keys.get(1),
                keys.size() == 3 ? keys.get(2) : null, photo.takenAt(), photo.latitude(),
                photo.longitude(), photo.deviceModel(), photo.originalSizeBytes(),
                photo.analyzeSizeBytes(), photo.previewSizeBytes(), photo.displaySizeBytes());
    }

    @Override
    public void close() {
        cleanup(directory);
    }

    public static void cleanup(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            files.forEach(PreparedPhotoDerivative::delete);
        } catch (IOException failure) {
            log.warn("임시 사진 디렉터리 조회 실패: path={}, cause={}", directory,
                    failure.getClass().getSimpleName());
        }
        delete(directory);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.warn("임시 사진 파일 삭제 실패: path={}, cause={}", file,
                    failure.getClass().getSimpleName());
        }
    }
}
