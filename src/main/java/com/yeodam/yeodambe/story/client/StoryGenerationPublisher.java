package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class StoryGenerationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;

    public CompletableFuture<SendResult<String, String>> publish(String payload) {
        String topic = topicProperties.topicFor(EventType.STORY_CREATE_REQUEST);
        var message = objectMapper.readValue(payload, StoryGenerationMessage.class);
        String key = message.tripId().toString();
        return kafkaTemplate.send(topic, key, payload);
    }
}
