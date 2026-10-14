package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.config.TripCreationKafkaConfiguration;
import com.yeodam.yeodambe.integration.service.KafkaDltLogService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class TripCreationKafkaConfigurationTest {

    @ParameterizedTest
    @CsvSource({"create,false", "update,false", "create,true", "update,true"})
    @SuppressWarnings("unchecked")
    void DLT_실패는_재처리하고_성공한_발행만_기록한다(String operation, boolean failFirst) {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var consumer = mock(Consumer.class);
        when(consumer.partitionsFor(eq("configured-trip-dlt"), any(Duration.class))).thenReturn(List.of(
                new PartitionInfo("configured-trip-dlt", 0, null, new Node[0], new Node[0]),
                new PartitionInfo("configured-trip-dlt", 1, null, new Node[0], new Node[0])
        ));
        var published = new ProducerRecord<>("configured-trip-dlt", 1, "7", "{broken");
        var metadata = new RecordMetadata(new TopicPartition("configured-trip-dlt", 1), 0, 0, 0, 1, 7);
        var successfulSend = CompletableFuture.completedFuture(new SendResult<>(published, metadata));
        if (failFirst) {
            when(kafka.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                    .thenReturn(successfulSend);
        } else {
            when(kafka.send(any(ProducerRecord.class))).thenReturn(successfulSend);
        }
        var dltLogs = mock(KafkaDltLogService.class);
        var configuration = new TripCreationKafkaConfiguration(dltLogs);
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(ConsumerFactory.class);
        var factory = "update".equals(operation)
                ? configuration.tripUpdateResultKafkaListenerContainerFactory(configurer, consumerFactory, kafka, "configured-trip-dlt")
                : configuration.tripResultKafkaListenerContainerFactory(configurer, consumerFactory, kafka, "configured-trip-dlt");
        var handler = (DefaultErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");
        var record = new ConsumerRecord<>("trip-result", 1, 10, "7", "{broken");

        if (failFirst) {
            assertThat(handler.handleOne(new IllegalArgumentException("invalid result"), record,
                    consumer, mock(MessageListenerContainer.class))).isFalse();
            verifyNoInteractions(dltLogs);
        }
        boolean recovered = handler.handleOne(
                new IllegalArgumentException("invalid result"),
                record,
                consumer,
                mock(MessageListenerContainer.class)
        );

        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
        assertThat(recovered).isTrue();
        verify(dltLogs).logPublished(eq(record), any(IllegalArgumentException.class), eq("configured-trip-dlt"));
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(failFirst ? 2 : 1)).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("configured-trip-dlt");
        assertThat(captor.getValue().partition()).isEqualTo(1);
        assertThat(captor.getValue().key()).isEqualTo("7");
        assertThat(captor.getValue().value()).isEqualTo("{broken");
    }
}
