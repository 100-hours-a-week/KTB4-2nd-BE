package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class StoryGenerationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;

    public CompletableFuture<SendResult<String, String>> publish(String payload) {
        String topic = topicProperties.topicFor(EventType.STORY_CREATE_REQUEST);
        return kafkaTemplate.send(topic, payload);
    }
}
