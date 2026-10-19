package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class MigrationTripMode {
    private final String modeFile;

    public MigrationTripMode(@Value("${migration.trip.mode-file:}") String modeFile) {
        this.modeFile = modeFile;
    }

    public boolean queueEnabled() {
        if (modeFile.isBlank()) {
            return false;
        }
        try {
            return switch (Files.readString(Path.of(modeFile)).trim()) {
                case "direct" -> false;
                case "queue" -> true;
                default -> throw unavailable();
            };
        } catch (IOException | SecurityException failure) {
            // A configured but unreadable marker must never fall back to the old DB writer.
            throw unavailable();
        }
    }

    private MigrationTripAdmissionException unavailable() {
        return new MigrationTripAdmissionException(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorMessage.MIGRATION_TRIP_MODE_UNAVAILABLE);
    }
}
