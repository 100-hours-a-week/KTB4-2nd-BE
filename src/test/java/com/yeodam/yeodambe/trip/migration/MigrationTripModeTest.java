package com.yeodam.yeodambe.trip.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTripModeTest {
    @TempDir Path directory;

    @Test
    void marker를_원자적으로_바꾼_뒤_다음_요청부터_queue를_선택한다() throws Exception {
        Path marker = directory.resolve("mode");
        Files.writeString(marker, "direct\n");
        MigrationTripMode mode = new MigrationTripMode(marker.toString());

        assertThat(mode.queueEnabled()).isFalse();
        Files.writeString(marker, "queue\n");
        assertThat(mode.queueEnabled()).isTrue();
    }

    @Test
    void 설정된_marker가_없거나_알수없는_값이면_옛_writer로_돌아가지_않는다() throws Exception {
        Path marker = directory.resolve("mode");
        MigrationTripMode mode = new MigrationTripMode(marker.toString());

        assertThatThrownBy(mode::queueEnabled)
                .isInstanceOf(MigrationTripAdmissionException.class);
        Files.writeString(marker, "unknown");
        assertThatThrownBy(mode::queueEnabled)
                .isInstanceOf(MigrationTripAdmissionException.class);
    }
}
