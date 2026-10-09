package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaEventConfiguration;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.story.event.StoryCreateRequestEvent;
import com.yeodam.yeodambe.story.event.StoryCreateResultEvent;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.TripUpdateRequestEvent;
import com.yeodam.yeodambe.trip.event.TripUpdateResultEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventStructureTest {

    @Test
    void 설정한_토픽을_이벤트_타입으로_조회한다() {
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaEventConfiguration.class)
                .withPropertyValues(
                        "app.kafka.topics.trip-create-request=dev.trip-create.request",
                        "app.kafka.topics.trip-create-result=dev.trip-create.result",
                        "app.kafka.topics.trip-update-request=dev.trip-update.request",
                        "app.kafka.topics.trip-update-result=dev.trip-update.result",
                        "app.kafka.topics.story-create-request=dev.story-create.request",
                        "app.kafka.topics.story-create-result=dev.story-create.result"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(KafkaTopicProperties.class);
                    assertThat(List.of(EventType.values()).stream().map(properties::topicFor))
                            .containsExactly(
                                    "dev.trip-create.request",
                                    "dev.trip-create.result",
                                    "dev.trip-update.request",
                                    "dev.trip-update.result",
                                    "dev.story-create.request",
                                    "dev.story-create.result"
                            );
                });
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void 토픽이_하나라도_누락되면_시작을_거부한다(EventType missingType) {
        String[] properties = Stream.of(EventType.values())
                .filter(type -> type != missingType)
                .map(type -> propertyName(type) + "=test." + type.name())
                .toArray(String[]::new);
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaEventConfiguration.class)
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(missingType.name());
                });
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void 토픽이_하나라도_공백이면_시작을_거부한다(EventType blankType) {
        String[] properties = Stream.of(EventType.values())
                .map(type -> propertyName(type) + "=" + (type == blankType ? " " : "test." + type.name()))
                .toArray(String[]::new);
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaEventConfiguration.class)
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(blankType.name());
                });
    }

    private String propertyName(EventType type) {
        return "app.kafka.topics." + type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Test
    void 각_도메인_이벤트가_자신의_타입을_반환한다() {
        List<EventPayload> events = List.of(
                new TripCreateRequestEvent(),
                new TripCreateResultEvent(),
                new TripUpdateRequestEvent(),
                new TripUpdateResultEvent(),
                new StoryCreateRequestEvent(),
                new StoryCreateResultEvent()
        );

        assertThat(events).extracting(EventPayload::eventType)
                .containsExactly(EventType.values());
    }

    @Test
    void 새_페이로드_구현체를_추가해도_내부_타입은_JSON에_포함되지_않는다() {
        EventPayload payload = new AdditionalPayload("예시");
        var mapper = JsonMapper.builder().build();

        assertThat(mapper.readTree(mapper.writeValueAsString(payload)))
                .isEqualTo(mapper.readTree("{\"value\":\"예시\"}"));
    }

    private record AdditionalPayload(String value) implements EventPayload {

        @Override
        public EventType eventType() {
            return EventType.TRIP_CREATE_REQUEST;
        }
    }
}
