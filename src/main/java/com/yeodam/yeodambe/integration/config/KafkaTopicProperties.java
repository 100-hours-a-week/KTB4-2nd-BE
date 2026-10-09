package com.yeodam.yeodambe.integration.config;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.stream.Stream;

@ConfigurationProperties(prefix = "app.kafka")
public record KafkaTopicProperties(
        Map<EventType, String> topics
) {

    public KafkaTopicProperties {
        topics = topics == null ? Map.of() : Map.copyOf(topics);
        Map<EventType, String> configuredTopics = topics;

        Stream.of(EventType.values()).forEach(type ->
                Assert.hasText(configuredTopics.get(type),
                        EventInternalErrorMessage.KAFKA_TOPIC_NOT_CONFIGURED.message().formatted(type)));
    }

    public String topicFor(EventType eventType) {
        Assert.notNull(eventType, EventInternalErrorMessage.EVENT_TYPE_MISSING.message());

        String topic = topics.get(eventType);

        Assert.hasText(topic, EventInternalErrorMessage.KAFKA_TOPIC_NOT_CONFIGURED.message().formatted(eventType));
        return topic;
    }
}
