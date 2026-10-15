package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class OutboxReceiptConsumer {
    private final StoryGenerationPublicationStateService receipts;

    @KafkaListener(
            topics = "${app.kafka.topics.story-create-request}",
            groupId = "${app.outbox.receipt-consumer-group:yeodam-outbox-receipts}",
            autoStartup = "${app.outbox.receipt-listener-enabled:true}",
            containerFactory = "outboxReceiptKafkaListenerContainerFactory",
            properties = {"enable.auto.commit=false", "auto.offset.reset=earliest", "isolation.level=read_committed"}
    )
    public void consume(ConsumerRecord<String, String> record) {
        Header source = record.headers().lastHeader("outbox-source");
        if (source == null) {
            return;
        }
        Assert.isTrue(record.timestampType() == TimestampType.LOG_APPEND_TIME
                && record.timestamp() >= 0, "CDC request topic must use LogAppendTime");
        String id = header(record.headers().lastHeader("id"));
        Assert.isTrue("story_generation_outbox".equals(header(source)), "Unexpected story Outbox source");
        receipts.recordPublished(Long.valueOf(id), new OutboxReceipt(
                record.topic(), record.key(), record.value(), Instant.ofEpochMilli(record.timestamp())));
    }

    private String header(Header header) {
        Assert.notNull(header, "CDC Outbox header is missing");
        Assert.notNull(header.value(), "CDC Outbox header value is missing");
        String value = new String(header.value(), StandardCharsets.UTF_8);
        Assert.hasText(value, "CDC Outbox header is empty");
        return value;
    }
}
