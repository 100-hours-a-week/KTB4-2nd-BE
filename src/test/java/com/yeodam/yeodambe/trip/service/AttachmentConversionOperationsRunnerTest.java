package com.yeodam.yeodambe.trip.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AttachmentConversionOperationsRunnerTest {
    private final AttachmentConversionMigrationService migration = mock(AttachmentConversionMigrationService.class);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("app.attachment-conversion.mode", "HOLD");
    private final AttachmentConversionOperationsRunner runner =
            new AttachmentConversionOperationsRunner(migration, environment, new ObjectMapper());

    @Test
    void 점검은_읽기_전용이며_근거가_없으면_전환을_거부한다() {
        assertThat(runner.execute(arguments("CHECK"))).isZero();
        assertThat(runner.execute(arguments("TRANSFER"))).isNotZero();
    }

    @Test
    void 잘못된_입력은_DB_조회_없이_거부한다() {
        assertThat(runner.execute(new DefaultApplicationArguments(
                "--app.attachment-conversion.operations.action=TRANSFER",
                "--app.attachment-conversion.operations.kind=INITIAL",
                "--app.attachment-conversion.operations.after-batch-id=-1",
                "--app.attachment-conversion.operations.limit=10"))).isNotZero();
        assertThat(runner.execute(new DefaultApplicationArguments(
                "--app.attachment-conversion.operations.action=UNKNOWN"))).isNotZero();
        verifyNoInteractions(migration);
    }

    private DefaultApplicationArguments arguments(String action) {
        return new DefaultApplicationArguments(
                "--app.attachment-conversion.operations.action=" + action,
                "--app.attachment-conversion.operations.kind=INITIAL",
                "--app.attachment-conversion.operations.after-batch-id=0",
                "--app.attachment-conversion.operations.limit=10");
    }
}
