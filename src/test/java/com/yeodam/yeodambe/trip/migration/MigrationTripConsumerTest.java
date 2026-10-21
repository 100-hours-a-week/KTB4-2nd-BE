package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MigrationTripConsumerTest {
    private static final String ID = "51961261-7761-4587-ae2b-55c0452924bd";
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final MigrationTripAdmissionService admission = mock(MigrationTripAdmissionService.class);
    private final MigrationTripApplyService apply = mock(MigrationTripApplyService.class);
    private final MigrationTripConsumer consumer = new MigrationTripConsumer(mapper, admission, apply);
    private final MigrationTripCommand command = new MigrationTripCommand(
            ID, 7L, "hash", "강릉", LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 2), List.of("42150"));

    @Test
    void key나_hash가_틀린_레코드는_적용하지_않는다() {
        when(mapper.readValue("json", MigrationTripCommand.class)).thenReturn(command);
        when(admission.hashPayload(7L, command.tripRequest())).thenReturn("hash");

        assertThatThrownBy(() -> consumer.consume("json", "other-id", 0))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    @Test
    void 영구_비즈니스_실패는_결과로_남긴다() {
        when(mapper.readValue("json", MigrationTripCommand.class)).thenReturn(command);
        when(admission.hashPayload(7L, command.tripRequest())).thenReturn("hash");
        doThrow(new TripNameDuplicatedException()).when(apply).apply(command);

        consumer.consume("json", "7", 0);

        verify(apply).recordFailure(command, "TRIP_NAME_DUPLICATED");
    }

    @Test
    void 완료된_요청만_구간시간과_파티션을_기록한다() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MigrationTripConsumer.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(mapper.readValue("json", MigrationTripCommand.class)).thenReturn(command);
            when(admission.hashPayload(7L, command.tripRequest())).thenReturn("hash");
            when(apply.apply(command)).thenReturn(true, false);
            consumer.consume("json", "7", 2);
            consumer.consume("json", "7", 2);
            var messages = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst()).contains("consumer_started_at_ms=", "apply_elapsed_us=", "partition=2");
            assertThat(messages.getLast()).contains("migration_trip_replayed").doesNotContain("apply_elapsed_us=");
            assertThat(messages.getFirst()).doesNotContain("강릉", "42150", "hash");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
