package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.story.event.StoryCreateRequestEvent;
import com.yeodam.yeodambe.story.event.StoryCreateResultEvent;
import com.yeodam.yeodambe.story.event.handler.StoryCreateRequestEventHandler;
import com.yeodam.yeodambe.story.event.handler.StoryCreateResultEventHandler;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.TripUpdateRequestEvent;
import com.yeodam.yeodambe.trip.event.TripUpdateResultEvent;
import com.yeodam.yeodambe.trip.event.handler.TripCreateRequestEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripCreateResultEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateRequestEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventHandlerTest {

    @Test
    void 도메인_핸들러가_서로_다른_이벤트_타입의_빈으로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        TripCreateRequestEventHandler.class,
                        TripCreateResultEventHandler.class,
                        TripUpdateRequestEventHandler.class,
                        TripUpdateResultEventHandler.class,
                        StoryCreateRequestEventHandler.class,
                        StoryCreateResultEventHandler.class
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(EventHandler.class).values())
                            .extracting(EventHandler::eventType)
                            .containsExactlyInAnyOrder(EventType.values());
                });
    }

    @Test
    void 미구현_핸들러는_이벤트를_처리한_것처럼_정상_종료하지_않는다() {
        assertNotImplemented(new TripCreateRequestEventHandler(), new TripCreateRequestEvent());
        assertNotImplemented(new TripCreateResultEventHandler(), new TripCreateResultEvent());
        assertNotImplemented(new TripUpdateRequestEventHandler(), new TripUpdateRequestEvent());
        assertNotImplemented(new TripUpdateResultEventHandler(), new TripUpdateResultEvent());
        assertNotImplemented(new StoryCreateRequestEventHandler(), new StoryCreateRequestEvent());
        assertNotImplemented(new StoryCreateResultEventHandler(), new StoryCreateResultEvent());
    }

    @Test
    void 지원하는_타입만_핸들러에_전달한다() {
        var handler = new RecordingHandler();
        var supported = new TestPayload(EventType.TRIP_CREATE_REQUEST);
        var unsupported = new TestPayload(EventType.STORY_CREATE_REQUEST);

        assertThat(handler.supports(supported.eventType())).isTrue();
        assertThat(handler.supports(unsupported.eventType())).isFalse();
        handler.handleIfSupported(supported);
        handler.handleIfSupported(unsupported);

        assertThat(handler.handledEvents).containsExactly(supported);
    }

    @Test
    void 페이로드가_없으면_처리하지_않고_검증_예외를_반환한다() {
        var handler = new RecordingHandler();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handleIfSupported(null))
                .withMessage(EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        assertThat(handler.handledEvents).isEmpty();
    }

    @Test
    void 구현체를_직접_호출해도_지원하지_않는_타입을_건너뛰고_null을_거부한다() {
        assertDirectInvocation(new TripCreateRequestEventHandler(), TripCreateRequestEvent.class);
        assertDirectInvocation(new TripCreateResultEventHandler(), TripCreateResultEvent.class);
        assertDirectInvocation(new TripUpdateRequestEventHandler(), TripUpdateRequestEvent.class);
        assertDirectInvocation(new TripUpdateResultEventHandler(), TripUpdateResultEvent.class);
        assertDirectInvocation(new StoryCreateRequestEventHandler(), StoryCreateRequestEvent.class);
        assertDirectInvocation(new StoryCreateResultEventHandler(), StoryCreateResultEvent.class);
    }

    private <T extends EventPayload> void assertDirectInvocation(EventHandler<T> handler, Class<T> eventClass) {
        T event = mock(eventClass);
        EventType unsupportedType = handler.eventType() == EventType.TRIP_CREATE_REQUEST
                ? EventType.STORY_CREATE_REQUEST : EventType.TRIP_CREATE_REQUEST;
        when(event.eventType()).thenReturn(unsupportedType);

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(null))
                .withMessage(EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
    }

    private <T extends EventPayload> void assertNotImplemented(EventHandler<T> handler, T event) {
        assertThat(handler.eventType()).isEqualTo(event.eventType());
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> handler.handleIfSupported(event))
                .withMessage(EventInternalErrorMessage.EVENT_HANDLER_NOT_IMPLEMENTED.message()
                        .formatted(event.eventType()));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> handler.handle(event))
                .withMessage(EventInternalErrorMessage.EVENT_HANDLER_NOT_IMPLEMENTED.message()
                        .formatted(event.eventType()));
    }

    private record TestPayload(EventType eventType) implements EventPayload {
    }

    private static class RecordingHandler implements EventHandler<TestPayload> {

        private final List<TestPayload> handledEvents = new ArrayList<>();

        @Override
        public EventType eventType() {
            return EventType.TRIP_CREATE_REQUEST;
        }

        @Override
        public void handle(TestPayload event) {
            handledEvents.add(event);
        }
    }
}
