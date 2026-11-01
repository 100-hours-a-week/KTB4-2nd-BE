package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.service.AttachmentConversionTransactionService;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("('${app.attachment-conversion.mode:DB}' == 'KAFKA' || '${app.attachment-conversion.mode:DB}' == 'HOLD')"
        + " && !${app.attachment-conversion.operations.enabled:false}")
public class AttachmentConversionReceiptListener {
    private final AttachmentConversionTransactionService transactions;

    @KafkaListener(
            id = "attachment-conversion-receipts",
            topics = {
                    "${app.attachment-conversion.topics.initial-light:yeodam-image-initial-light}",
                    "${app.attachment-conversion.topics.initial-heic:yeodam-image-initial-heic}",
                    "${app.attachment-conversion.topics.additional-light:yeodam-image-additional-light}",
                    "${app.attachment-conversion.topics.additional-heic:yeodam-image-additional-heic}"
            },
            groupId = "${app.attachment-conversion.group-prefix:yeodam-image-conversion}-receipts",
            containerFactory = "attachmentConversionReceiptKafkaListenerContainerFactory",
            properties = {"isolation.level=read_committed"}
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Assert.isTrue("attachment_conversion_outbox".equals(header(record, "outbox-source")),
                    "변환 Outbox 출처가 일치하지 않습니다.");
            String id = header(record, "id");
            Assert.hasText(record.key(), "변환 receipt key가 필요합니다.");
            Assert.hasText(record.value(), "변환 receipt payload가 필요합니다.");
            Assert.isTrue(record.partition() >= 0 && record.offset() >= 0 && record.timestamp() >= 0
                    && record.timestampType() == TimestampType.LOG_APPEND_TIME, "변환 receipt 위치가 유효하지 않습니다.");
            // ConsumerRecord has no topic identity; current Admin metadata cannot identify a buffered observation.
            transactions.recordPublished(new AttachmentConversionPublication(id, record.topic(), record.key(), record.value(),
                    null, record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp()), record.leaderEpoch().orElse(null)));
        } catch (IllegalArgumentException | InvalidAttachmentConversionMessageException invalid) {
            log.warn("이미지 변환 발행 관측 무시: failure=INVALID_RECEIPT");
        }
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        Assert.isTrue(header != null && header.value() != null, "변환 receipt header가 필요합니다.");
        String value = new String(header.value(), StandardCharsets.UTF_8);
        Assert.hasText(value, "변환 receipt header가 비어 있습니다.");
        return value;
    }
}
