package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.client.OutboxReceiptConsumer;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.integration.service.OutboxReceiptService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OutboxReceiptConsumerTest {
    private final OutboxReceiptService receipts = mock(OutboxReceiptService.class);
    private final OutboxReceiptConsumer consumer = new OutboxReceiptConsumer(receipts);

    @Test
    void forwardsBrokerAppendTimeRatherThanConsumerObservationTime() {
        var headers = new RecordHeaders();
        headers.add("outbox-source", "story_generation_outbox".getBytes(StandardCharsets.UTF_8));
        headers.add("id", "7".getBytes(StandardCharsets.UTF_8));
        var record = new ConsumerRecord<>("requests", 0, 0L, 1_800_000_000_000L,
                TimestampType.LOG_APPEND_TIME, -1, -1, "77", "{}", headers, Optional.empty());
        consumer.consume(record);
        var receipt = ArgumentCaptor.forClass(
                OutboxReceipt.class);
        org.mockito.Mockito.verify(receipts).observe(org.mockito.ArgumentMatchers.eq("story_generation_outbox"),
                org.mockito.ArgumentMatchers.eq("7"), receipt.capture());
        org.assertj.core.api.Assertions.assertThat(receipt.getValue().publishedAt())
                .isEqualTo(Instant.parse("2027-01-15T08:00:00Z"));
    }

    @Test
    void ignoresHistoricalRequestWithoutCdcHeaders() {
        consumer.consume(new ConsumerRecord<>("requests", 0, 0L, "77", "{}"));

        verifyNoInteractions(receipts);
    }

    @Test
    void rejectsCreateTimeRatherThanStartingAnalysisFromSourceTime() {
        var headers = new RecordHeaders();
        headers.add("outbox-source", "story_generation_outbox".getBytes(StandardCharsets.UTF_8));
        headers.add("id", "7".getBytes(StandardCharsets.UTF_8));
        var record = new ConsumerRecord<>("requests", 0, 0L, 1_800_000_000_000L,
                TimestampType.CREATE_TIME, -1, -1, "77", "{}", headers, Optional.empty());

        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(receipts);
    }
}
