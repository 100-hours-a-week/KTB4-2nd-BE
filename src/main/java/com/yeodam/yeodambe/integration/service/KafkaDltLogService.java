package com.yeodam.yeodambe.integration.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

@Slf4j
@Service
public class KafkaDltLogService {

    public void logPublished(ConsumerRecord<?, ?> record, Exception failure, String deadLetterTopic) {
        log.atWarn()
                .addKeyValue("event", "kafka_dlt_published")
                .addKeyValue("service", "be")
                .addKeyValue("dlt_topic", deadLetterTopic)
                .addKeyValue("source_topic", record.topic())
                .addKeyValue("trip_id", tripId(record.key()))
                .addKeyValue("failure", processingFailure(failure).getClass().getSimpleName())
                .addKeyValue("attempts", deliveryAttempts(record))
                .log("Kafka 결과를 DLT로 발행했습니다.");
    }

    private String tripId(Object key) {
        if (!(key instanceof String value)) {
            return null;
        }

        try {
            return Long.parseLong(value) > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Exception processingFailure(Exception failure) {
        Exception current = failure;
        while (current instanceof ListenerExecutionFailedException
                && current.getCause() instanceof Exception cause) {
            current = cause;
        }
        return current;
    }

    private int deliveryAttempts(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return 1;
        }

        int attempts = ByteBuffer.wrap(header.value()).getInt();
        return attempts > 0 ? attempts : 1;
    }
}
