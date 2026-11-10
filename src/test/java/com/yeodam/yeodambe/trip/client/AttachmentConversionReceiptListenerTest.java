package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.service.AttachmentConversionService;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AttachmentConversionReceiptListenerTest {
    private final AttachmentConversionService transactions = mock(AttachmentConversionService.class);
    private final AttachmentConversionReceiptListener listener = new AttachmentConversionReceiptListener(transactions);

    @Test
    void 토픽_식별자를_추론하지_않고_레코드_위치를_보존한다() {
        var record = record("attachment_conversion_outbox", "id", TimestampType.LOG_APPEND_TIME, 0, 12);
        listener.consume(record);
        verify(transactions).recordPublished(new AttachmentConversionPublication("id", record.topic(), "7", "DO_NOT_LOG",
                null, 0, 12, java.time.Instant.ofEpochMilli(1000), 0));
    }

    @Test
    void 잘못된_헤더와_위치는_제어_그룹을_막지_않는다(CapturedOutput output) {
        for (var record : java.util.List.of(
                record(null, "id", TimestampType.LOG_APPEND_TIME, 0, 12),
                record("story_generation_outbox", "id", TimestampType.LOG_APPEND_TIME, 0, 12),
                record("attachment_conversion_outbox", null, TimestampType.LOG_APPEND_TIME, 0, 12),
                record("attachment_conversion_outbox", " ", TimestampType.LOG_APPEND_TIME, 0, 12),
                record("attachment_conversion_outbox", "id", TimestampType.CREATE_TIME, 0, 12),
                record("attachment_conversion_outbox", "id", TimestampType.LOG_APPEND_TIME, -1, 12),
                record("attachment_conversion_outbox", "id", TimestampType.LOG_APPEND_TIME, 0, -1))) {
            assertThatCode(() -> listener.consume(record)).doesNotThrowAnyException();
        }
        verifyNoInteractions(transactions);
        assertThat(output.getAll()).doesNotContain("DO_NOT_LOG");
    }

    @Test
    void 영구적인_불일치는_본문이나_예외를_로그에_남기지_않고_건너뛴다(CapturedOutput output) {
        doThrow(new IllegalArgumentException("DO_NOT_LOG")).when(transactions).recordPublished(any());
        assertThatCode(() -> listener.consume(record("attachment_conversion_outbox", "id", TimestampType.LOG_APPEND_TIME, 0, 12)))
                .doesNotThrowAnyException();
        assertThat(output.getAll()).contains("INVALID_RECEIPT").doesNotContain("DO_NOT_LOG");
    }

    @Test
    void DB_실패는_팩토리의_무제한_재시도를_위해_전파한다() {
        var failure = new TransientDataAccessResourceException("DO_NOT_LOG");
        doThrow(failure).when(transactions).recordPublished(any());
        assertThatThrownBy(() -> listener.consume(record("attachment_conversion_outbox", "id", TimestampType.LOG_APPEND_TIME, 0, 12)))
                .isSameAs(failure);
    }

    private ConsumerRecord<String, String> record(String source, String id, TimestampType timestampType, int partition, long offset) {
        var headers = new RecordHeaders();
        if (source != null) {
            headers.add("outbox-source", source.getBytes(StandardCharsets.UTF_8));
        }
        if (id != null) {
            headers.add("id", id.getBytes(StandardCharsets.UTF_8));
        }
        return new ConsumerRecord<>("yeodam-image-initial-light", partition, offset, 1000,
                timestampType, 0, 0, "7", "DO_NOT_LOG", headers, Optional.of(0));
    }
}
