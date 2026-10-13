package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.client.AdditionalAttachmentOutboxPublisher;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentDeliveryService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentKafkaResultService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdditionalAttachmentKafkaTopicTest {
    @Test
    @SuppressWarnings("unchecked")
    void publishesAdditionalPayloadToConfiguredUpdateTopicWithKeyAndRequestId() {
        var delivery = mock(AdditionalAttachmentDeliveryService.class);
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var topics = new KafkaTopicProperties(Arrays.stream(EventType.values()).collect(Collectors.toMap(
                type -> type, type -> "configured." + type)));
        var claim = new AdditionalAttachmentDeliveryService.Claim(1L, "execution", 7L, "token", "{payload}", "request-123");
        when(delivery.claim()).thenReturn(Optional.of(claim), Optional.empty());
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        new AdditionalAttachmentOutboxPublisher(delivery, kafka, topics).publishPending();
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        var record = captor.getValue();
        assertThat(record.topic()).isEqualTo("configured.TRIP_UPDATE_REQUEST");
        assertThat(record.key()).isEqualTo("7");
        assertThat(record.value()).isEqualTo("{payload}");
        assertThat(new String(record.headers().lastHeader("X-Request-ID").value(), StandardCharsets.UTF_8)).isEqualTo("request-123");
        verify(delivery).finish(claim, true, null);
    }

    @Test
    void consumesUpdateResultWithoutChangingWireFormat() {
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
    void malformedUpdateResultFailsBeforeDatabaseHandling() {
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
    void listenerUsesUpdateTopicAndItsOwnDeadLetterFactory() throws Exception {
        var annotation = TripUpdateResultEventHandler.class.getMethod("consume", String.class).getAnnotation(KafkaListener.class);
        assertThat(annotation.topics()).containsExactly("${app.kafka.topics.trip-update-result}");
        assertThat(annotation.containerFactory()).isEqualTo("tripUpdateResultKafkaListenerContainerFactory");
    }
}
