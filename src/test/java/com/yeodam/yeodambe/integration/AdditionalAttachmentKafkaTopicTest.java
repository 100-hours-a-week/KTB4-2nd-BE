package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.client.OutboxReceiptConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdditionalAttachmentKafkaTopicTest {
    @Test
    void CDC_리스너는_설정된_추가_사진_요청_토픽을_구독한다() throws Exception {
        var annotation = OutboxReceiptConsumer.class.getMethod("consume", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);
        assertThat(annotation.topics()).contains("${app.kafka.topics.trip-update-request}");
        assertThat(annotation.containerFactory()).isEqualTo("outboxReceiptKafkaListenerContainerFactory");
    }

    @Test
    void 전송_형식을_바꾸지_않고_추가_사진_결과를_소비한다() {
        var results = mock(AdditionalAttachmentKafkaResultService.class);
        var handler = new TripUpdateResultEventHandler(results, new ObjectMapper());
        String raw = """
                {"type":"process_result","trip_id":7,"execution_id":"addition",
                 "status":"CANCELED","result":null,"error":null}
                """;
        handler.consume(raw);
        verify(results).receive(argThat(event -> event.tripId() == 7L && event.executionId().equals("addition")), eq(raw));
    }

    @Test
    void 잘못된_추가_사진_결과는_DB_처리_전에_실패한다() {
        var results = mock(AdditionalAttachmentKafkaResultService.class);
        var handler = new TripUpdateResultEventHandler(results, new ObjectMapper());
        assertThatIllegalArgumentException().isThrownBy(() -> handler.consume("{broken"))
                .withMessage("TRIP_RESULT_TRANSPORT_INVALID");
        assertThatIllegalArgumentException().isThrownBy(() -> handler.consume("""
                {"type":"process_result","trip_id":"7","execution_id":"addition",
                 "status":"CANCELED","result":null,"error":null}
                """));
        verifyNoInteractions(results);
    }

    @Test
    void 리스너는_추가_사진_결과_토픽과_전용_실패_처리_팩토리를_사용한다() throws Exception {
        var annotation = TripUpdateResultEventHandler.class.getMethod("consume", String.class).getAnnotation(KafkaListener.class);
        assertThat(annotation.topics()).containsExactly("${app.kafka.topics.trip-update-result}");
        assertThat(annotation.containerFactory()).isEqualTo("tripUpdateResultKafkaListenerContainerFactory");
    }
}
