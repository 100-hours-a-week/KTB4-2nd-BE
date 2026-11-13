package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.integration.service.OutboxReceiptService;
import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OutboxReceiptTraceTest {
    @Test
    void photos_ready_수신에서_최종_dispatch로_넘어가는_동기_경계에_원래_부모를_복원한다() {
        var receipts = mock(OutboxReceiptService.class);
        var observed = new AtomicReference<String>();
        doAnswer(call -> { observed.set(new WorkflowTraceService().capture().traceparent()); return null; })
                .when(receipts).observe(anyString(), anyString(), any());
        var headers = new RecordHeaders();
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        headers.add("outbox-source", "trip_creation_outbox".getBytes(StandardCharsets.UTF_8));
        headers.add("id", "fixture".getBytes(StandardCharsets.UTF_8));
        headers.add("traceparent", parent.getBytes(StandardCharsets.UTF_8));
        var record = new ConsumerRecord<String, String>("request", 0, 0, 1000L,
                TimestampType.LOG_APPEND_TIME, 1, 2, "key", "{}", headers, Optional.empty());
        new OutboxReceiptConsumer(receipts).consume(record);
        assertThat(observed.get()).isEqualTo(parent);
        assertThat(new WorkflowTraceService().capture().traceparent()).isNull();
        // Old messages remain valid and cannot inherit a previous record's Context.
        headers.remove("traceparent");new OutboxReceiptConsumer(receipts).consume(record);
        assertThat(observed.get()).isNull();
    }
}
