package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.story.event.StoryCreateRequestEvent;
import com.yeodam.yeodambe.story.event.handler.StoryCreateRequestEventHandler;
import com.yeodam.yeodambe.story.event.handler.StoryCreateResultEventHandler;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.StoryGenerationCompletionService;
import com.yeodam.yeodambe.story.service.StoryGenerationTerminalService;
import com.yeodam.yeodambe.trip.event.TripUpdateRequestEvent;
import com.yeodam.yeodambe.trip.event.TripUpdateResultEvent;
import com.yeodam.yeodambe.trip.event.handler.TripCreateRequestEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripCreateResultEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateRequestEventHandler;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class EventHandlerTest {

    @Test
    void 여행_결과의_ID와_상태를_다른_JSON_타입에서_강제_변환하지_않는다() {
        var results = mock(TripAnalysisResultService.class);
        var handler = new TripCreateResultEventHandler(results, new ObjectMapper());
        for (String tripId : List.of("7.5", "\"7\"", "9223372036854775808")) {
            String raw = """
                    {"type":"process_result","trip_id":%s,"execution_id":"run",
                     "status":"CANCELED","result":null,"error":null}
                    """.formatted(tripId);
            assertThatIllegalArgumentException().isThrownBy(() -> handler.consume(raw))
                    .withMessage("TRIP_RESULT_TRANSPORT_INVALID");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> handler.consume("""
                {"type":"process_result","trip_id":7,"execution_id":123,
                 "status":"CANCELED","result":null,"error":null}
                """));
        assertThatIllegalArgumentException().isThrownBy(() -> handler.consume("""
                {"type":"process_result","trip_id":7,"execution_id":"run",
                 "status":0,"result":{},"error":null}
                """));
        Mockito.verifyNoInteractions(results);
    }

    @Test
    void 도메인_핸들러가_서로_다른_이벤트_타입의_빈으로_등록된다() {
        new ApplicationContextRunner()
                .withBean(InitialAttachmentUploadTransactionService.class, () -> mock(InitialAttachmentUploadTransactionService.class))
                .withBean(TripAnalysisResultService.class, () -> mock(TripAnalysisResultService.class))
                .withBean(com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService.class,
                        () -> mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StoryGenerationJobRepository.class, () -> mock(StoryGenerationJobRepository.class))
                .withBean(StoryGenerationCompletionService.class, () -> mock(StoryGenerationCompletionService.class))
                .withBean(StoryGenerationTerminalService.class, () -> mock(StoryGenerationTerminalService.class))
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
        assertNotImplemented(new TripUpdateRequestEventHandler(), new TripUpdateRequestEvent());
        assertNotImplemented(new TripUpdateResultEventHandler(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService.class), new ObjectMapper()), new TripUpdateResultEvent());
        assertNotImplemented(new StoryCreateRequestEventHandler(), new StoryCreateRequestEvent());
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
    void 구현체를_직접_호출해도_null_페이로드를_거부한다() {
        직접_호출시_null_거절_검증(requestHandler());
        직접_호출시_null_거절_검증(resultHandler());
        직접_호출시_null_거절_검증(new TripUpdateRequestEventHandler());
        직접_호출시_null_거절_검증(new TripUpdateResultEventHandler(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService.class), new ObjectMapper()));
        직접_호출시_null_거절_검증(new StoryCreateRequestEventHandler());
        직접_호출시_null_거절_검증(storyResultHandler());
    }

    private TripCreateRequestEventHandler requestHandler() {
        return new TripCreateRequestEventHandler(mock(InitialAttachmentUploadTransactionService.class));
    }

    private TripCreateResultEventHandler resultHandler() {
        return new TripCreateResultEventHandler(mock(TripAnalysisResultService.class),
                new ObjectMapper());
    }

    private StoryCreateResultEventHandler storyResultHandler() {
        return new StoryCreateResultEventHandler(mock(StoryGenerationJobRepository.class),
                mock(StoryGenerationCompletionService.class), mock(StoryGenerationTerminalService.class));
    }

    private void 직접_호출시_null_거절_검증(EventHandler<?> handler) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(null))
                .withMessage(EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
    }

    private <T extends EventPayload> void assertNotImplemented(
            EventHandler<T> handler,
            T event
    ) {
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
