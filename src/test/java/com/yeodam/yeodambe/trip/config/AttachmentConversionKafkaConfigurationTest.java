package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.client.AttachmentConversionKafkaListener;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentConversionKafkaConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AttachmentConversionKafkaConfiguration.class)
            .withBean("conversionService", ConversionService.class, ApplicationConversionService::new)
            .withBean(KafkaListenerEndpointRegistry.class, KafkaListenerEndpointRegistry::new)
            .withBean(ConsumerFactory.class, () -> new DefaultKafkaConsumerFactory<>(Map.of(
                    "bootstrap.servers", "localhost:9092", "auto.offset.reset", "latest", "enable.auto.commit", true,
                    "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class)))
            .withBean(ConcurrentKafkaListenerContainerFactoryConfigurer.class, () -> new ConcurrentKafkaListenerContainerFactoryConfigurer() {
                @Override
                public void configure(ConcurrentKafkaListenerContainerFactory<Object, Object> factory,
                        ConsumerFactory<Object, Object> consumers) {
                    factory.setConsumerFactory(consumers);
                }
            });

    @Test
    void HOLD_수신_확인은_워커와_독립적이며_안전하지_않은_전역_컨슈머_설정을_덮어쓴다() {
        context.withPropertyValues("app.attachment-conversion.mode=HOLD", "app.attachment-conversion.retry.technical-backoff=100ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasBean("attachmentConversionReceiptKafkaListenerContainerFactory")
                            .doesNotHaveBean("attachmentConversionKafkaListenerContainerFactory")
                            .doesNotHaveBean(AttachmentConversionKafkaListener.class);
                    var receipt = ctx.getBean("attachmentConversionReceiptKafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(receipt.getConsumerFactory().getConfigurationProperties())
                            .containsEntry("enable.auto.commit", false).containsEntry("auto.offset.reset", "earliest");
                    assertThat(ctx.getBean(ConsumerFactory.class).getConfigurationProperties())
                            .containsEntry("enable.auto.commit", true).containsEntry("auto.offset.reset", "latest");
                });
    }

    @Test
    void Kafka_작업_팩토리는_독립적인_비동기_단일_레코드_컨테이너_네_개를_사용한다() {
        configured().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var factory = ctx.getBean("attachmentConversionKafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(factory.getConsumerFactory().getConfigurationProperties()).containsEntry("enable.auto.commit", false)
                    .containsEntry("auto.offset.reset", "earliest").containsEntry("max.poll.records", 1)
                    .containsEntry("fetch.max.bytes", 1048576).containsEntry("max.partition.fetch.bytes", 524288);
            assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
            assertThat(factory.getContainerProperties().isAsyncAcks()).isTrue();
            assertThat(factory.getContainerProperties().isSyncCommits()).isTrue();
            assertThat(factory.getContainerProperties().isPauseImmediate()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB", "HOLD", "KAFKA"})
    void 운영_모드는_변환_Kafka_팩토리를_생성하지_않는다(String mode) {
        context.withPropertyValues("app.attachment-conversion.mode=" + mode,
                "app.attachment-conversion.operations.enabled=true").run(ctx -> assertThat(ctx).hasNotFailed()
                .doesNotHaveBean(AttachmentConversionKafkaConfiguration.class));
    }

    @Test
    void 기본_DB_모드는_Kafka_용량과_재시도_설정을_요구하지_않는다() {
        context.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AttachmentConversionKafkaConfiguration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.attachment-conversion.kafka.fetch-max-bytes=0", "app.attachment-conversion.kafka.partition-fetch-max-bytes=-1",
            "app.attachment-conversion.kafka.max-message-bytes=0", "app.attachment-conversion.retry.technical-backoff=0ms"})
    void Kafka는_잘못된_필수_용량이나_백오프_설정을_거부한다(String property) {
        configured().withPropertyValues(property).run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void 회수된_파티션의_늦은_커밋은_새_할당을_중지하거나_커밋하지_않는다(boolean lost) {
        configured().withPropertyValues("app.attachment-conversion.retry.technical-backoff=1ns").run(ctx -> {
            var factory = ctx.getBean("attachmentConversionKafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            Consumer<Object, Object> delegate = mock(Consumer.class);
            ConsumerFactory<Object, Object> consumers = factory.getConsumerFactory();
            Consumer<Object, Object> consumer = consumers.getPostProcessors().getLast().apply(delegate);
            var revoked = new TopicPartition("old-work", 0);
            var assigned = new TopicPartition("new-work", 0);
            var rebalance = new AtomicReference<ConsumerRebalanceListener>();
            doAnswer(invocation -> {
                rebalance.set(invocation.getArgument(1));
                return null;
            }).when(delegate).subscribe(anyCollection(), any(ConsumerRebalanceListener.class));
            when(delegate.assignment()).thenReturn(Set.of(revoked));
            doThrow(new TimeoutException("commit unavailable")).when(delegate).commitSync(anyMap(), any(Duration.class));
            consumer.subscribe(List.of("old-work"), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    consumer.commitSync(Map.of(revoked, new OffsetAndMetadata(1)), Duration.ofSeconds(1));
                }

                @Override
                public void onPartitionsLost(Collection<TopicPartition> partitions) {
                    onPartitionsRevoked(partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                }
            });
            if (lost) {
                rebalance.get().onPartitionsLost(Set.of(revoked));
            } else {
                rebalance.get().onPartitionsRevoked(Set.of(revoked));
            }
            when(delegate.assignment()).thenReturn(Set.of(assigned));
            rebalance.get().onPartitionsAssigned(Set.of(assigned));
            clearInvocations(delegate);
            for (int poll = 0; poll < 3; poll++) {
                consumer.poll(Duration.ZERO);
            }
            verify(delegate, never()).commitSync(anyMap(), any(Duration.class));
            verify(delegate, never()).pause(anyCollection());
        });
    }

    private ApplicationContextRunner configured() {
        return context.withBean(AttachmentConversionKafkaListener.class, () -> mock(AttachmentConversionKafkaListener.class))
                .withPropertyValues("app.attachment-conversion.mode=KAFKA", "app.attachment-conversion.retry.technical-backoff=100ms",
                        "app.attachment-conversion.kafka.fetch-max-bytes=1048576", "app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
                        "app.attachment-conversion.kafka.max-message-bytes=262144");
    }
}
