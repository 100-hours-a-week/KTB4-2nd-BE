package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.config.TripCreationKafkaConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

class TripCreationKafkaConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void 설정한_DLT로_원래_파티션과_메시지를_유지해_발행한다() {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var consumer = mock(Consumer.class);
        when(consumer.partitionsFor(eq("configured-trip-dlt"), any(Duration.class))).thenReturn(List.of(
                new PartitionInfo("configured-trip-dlt", 0, null, new Node[0], new Node[0]),
                new PartitionInfo("configured-trip-dlt", 1, null, new Node[0], new Node[0])
        ));
        var published = new ProducerRecord<>("configured-trip-dlt", 1, "7", "{broken");
        var metadata = new RecordMetadata(new TopicPartition("configured-trip-dlt", 1), 0, 0, 0, 1, 7);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(published, metadata)));
        var factory = new TripCreationKafkaConfiguration().tripResultKafkaListenerContainerFactory(
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class),
                mock(ConsumerFactory.class),
                kafka,
                "configured-trip-dlt"
        );
        var handler = (DefaultErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");
        var record = new ConsumerRecord<>("trip-result", 1, 10, "7", "{broken");

        boolean recovered = handler.handleOne(
                new IllegalArgumentException("invalid result"),
                record,
                consumer,
                mock(MessageListenerContainer.class)
        );

        assertThat(recovered).isTrue();
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("configured-trip-dlt");
        assertThat(captor.getValue().partition()).isEqualTo(1);
        assertThat(captor.getValue().key()).isEqualTo("7");
        assertThat(captor.getValue().value()).isEqualTo("{broken");
    }
}
