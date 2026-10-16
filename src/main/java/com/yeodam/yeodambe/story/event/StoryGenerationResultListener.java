package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.integration.event.EventJsonCodec;
import com.yeodam.yeodambe.story.event.handler.StoryCreateResultEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.story.result-listener-enabled",
        havingValue = "true"
)
public class StoryGenerationResultListener {

    private final ObjectMapper objectMapper;
    private final EventJsonCodec eventJsonCodec;
    private final StoryCreateResultEventHandler resultHandler;

    @KafkaListener(
            topics = "${app.kafka.topics.story-create-result}",
            groupId = "${app.story.result-consumer-group}",
            containerFactory = "storyResultKafkaListenerContainerFactory"
    )
    public void receive(String message) {
        StoryCreateResultEvent event;
        try {
            Assert.hasText(message, EventInternalErrorMessage.EVENT_JSON_BLANK.message());
            var root = objectMapper.readTree(message);
            Assert.notNull(root, EventInternalErrorMessage.EVENT_JSON_NULL_PAYLOAD.message());
            String type = root.path("type").asString();
            Assert.hasText(type, EventInternalErrorMessage.EVENT_TYPE_MISSING.message());
            if (!"story_result".equals(type)) {
                return;
            }
            event = eventJsonCodec.deserialize(message, StoryCreateResultEvent.class);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new InvalidStoryResultException("스토리 결과 메시지를 읽을 수 없습니다.", failure);
        }

        resultHandler.handle(event);
    }
}