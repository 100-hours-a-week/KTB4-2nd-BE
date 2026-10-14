package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.config.TripCreationKafkaConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripCreationKafkaConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void 일시적_오류는_세번_재시도한_뒤_DLT로_발행한다(String operation) {
        var fixture = retryFixture(operation);
        when(fixture.kafka().send(any(ProducerRecord.class)))
                .thenReturn(successfulPublish());
        var failure = new IllegalStateException("database unavailable");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(fixture.handler().handleOne(
                    failure, fixture.record(), fixture.consumer(), fixture.container()))
                    .isFalse();
            verify(fixture.kafka(), never()).send(any(ProducerRecord.class));
        }

        assertThat(fixture.handler().handleOne(
                failure, fixture.record(), fixture.consumer(), fixture.container()))
                .isTrue();
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(fixture.kafka(), times(1)).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("configured-trip-dlt");
        assertThat(captor.getValue().partition()).isEqualTo(1);
        assertThat(captor.getValue().key()).isEqualTo("7");
        assertThat(captor.getValue().value()).isEqualTo("result-json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void DLT_발행이_실패하면_복구_완료로_처리하지_않는다(String operation) {
        var fixture = retryFixture(operation);
        when(fixture.kafka().send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .thenReturn(successfulPublish());
        var failure = new IllegalArgumentException("invalid result");

        assertThat(fixture.handler().handleOne(
                failure, fixture.record(), fixture.consumer(), fixture.container()))
                .isFalse();
        assertThat(fixture.handler().handleOne(
                failure, fixture.record(), fixture.consumer(), fixture.container()))
                .isTrue();
        verify(fixture.kafka(), times(2)).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void 잘못된_JSON은_재시도하지_않고_DLT로_발행한다(String operation) {
        var fixture = retryFixture(operation);
        when(fixture.kafka().send(any(ProducerRecord.class)))
                .thenReturn(successfulPublish());
        var failure = assertThrows(JacksonException.class,
                () -> new ObjectMapper().readTree("{broken"));

        assertThat(fixture.handler().handleOne(
                failure, fixture.record(), fixture.consumer(), fixture.container()))
                .isTrue();
        verify(fixture.kafka(), times(1)).send(any(ProducerRecord.class));
    }

    private CompletableFuture<SendResult<String, String>> successfulPublish() {
        return CompletableFuture.completedFuture(new SendResult<>(
                new ProducerRecord<>("configured-trip-dlt", 1, "7", "result-json"),
                new RecordMetadata(new TopicPartition("configured-trip-dlt", 1), 0, 0, 0, 1, 7)
        ));
    }

    @SuppressWarnings("unchecked")
    private RetryFixture retryFixture(String operation) {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var consumer = (Consumer<String, String>) mock(Consumer.class);
        when(consumer.partitionsFor(eq("configured-trip-dlt"), any(Duration.class))).thenReturn(List.of(
                new PartitionInfo("configured-trip-dlt", 0, null, new Node[0], new Node[0]),
                new PartitionInfo("configured-trip-dlt", 1, null, new Node[0], new Node[0])
        ));
        var configuration = new TripCreationKafkaConfiguration();
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(ConsumerFactory.class);
        var factory = "update".equals(operation)
                ? configuration.tripUpdateResultKafkaListenerContainerFactory(
                        configurer, consumerFactory, kafka, "configured-trip-dlt")
                : configuration.tripResultKafkaListenerContainerFactory(
                        configurer, consumerFactory, kafka, "configured-trip-dlt");
        return new RetryFixture(
                kafka,
                consumer,
                mock(MessageListenerContainer.class),
                (DefaultErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler"),
                new ConsumerRecord<>("trip-result", 1, 10, "7", "result-json")
        );
    }

    private record RetryFixture(
            KafkaTemplate<String, String> kafka,
            Consumer<String, String> consumer,
            MessageListenerContainer container,
            DefaultErrorHandler handler,
            ConsumerRecord<String, String> record
    ) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    @SuppressWarnings("unchecked")
    void 설정한_DLT로_원래_파티션과_메시지를_유지해_발행한다(String operation) {
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
        var configuration = new TripCreationKafkaConfiguration();
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(ConsumerFactory.class);
        var factory = "update".equals(operation)
                ? configuration.tripUpdateResultKafkaListenerContainerFactory(configurer, consumerFactory, kafka, "configured-trip-dlt")
                : configuration.tripResultKafkaListenerContainerFactory(configurer, consumerFactory, kafka, "configured-trip-dlt");
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
