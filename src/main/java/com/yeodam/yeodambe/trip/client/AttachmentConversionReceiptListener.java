package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.AttachmentConversionService;
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
        + " && !${app.attachment-conversion.operations.enabled:false}"
        + " && ${app.attachment-conversion.receipt-listener-enabled:true}")
public class AttachmentConversionReceiptListener {
    private final AttachmentConversionService transactions;

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
                    TripInternalErrorMessage.CONVERSION_OUTBOX_SOURCE_MISMATCH.message());
            String id = header(record, "id");
            Assert.hasText(record.key(), TripInternalErrorMessage.CONVERSION_RECEIPT_KEY_MISSING.message());
            Assert.hasText(record.value(), TripInternalErrorMessage.CONVERSION_RECEIPT_PAYLOAD_MISSING.message());
            Assert.isTrue(record.partition() >= 0 && record.offset() >= 0 && record.timestamp() >= 0
                    && record.timestampType() == TimestampType.LOG_APPEND_TIME,
                    TripInternalErrorMessage.CONVERSION_RECEIPT_POSITION_INVALID.message());
            // ConsumerRecord has no topic identity; current Admin metadata cannot identify a buffered observation.
            transactions.recordPublished(new AttachmentConversionPublication(id, record.topic(), record.key(), record.value(),
                    null, record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp()), record.leaderEpoch().orElse(null)));
        } catch (IllegalArgumentException | InvalidAttachmentConversionMessageException invalid) {
            log.warn(TripInternalErrorMessage.CONVERSION_RECEIPT_IGNORED.message());
        }
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        Assert.isTrue(header != null && header.value() != null,
                TripInternalErrorMessage.CONVERSION_RECEIPT_HEADER_MISSING.message());
        String value = new String(header.value(), StandardCharsets.UTF_8);
        Assert.hasText(value, TripInternalErrorMessage.CONVERSION_RECEIPT_HEADER_EMPTY.message());
        return value;
    }
}
