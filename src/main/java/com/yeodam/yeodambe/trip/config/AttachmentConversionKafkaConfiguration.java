package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.client.AttachmentConversionKafkaListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!${app.attachment-conversion.operations.enabled:false} && ("
        + "('${app.attachment-conversion.mode:DB}' == 'KAFKA' && ${app.attachment-conversion.consumer-enabled:true})"
        + " || (('${app.attachment-conversion.mode:DB}' == 'KAFKA' || '${app.attachment-conversion.mode:DB}' == 'HOLD')"
        + " && ${app.attachment-conversion.receipt-listener-enabled:true}))")
public class AttachmentConversionKafkaConfiguration {
    private final Duration backoff;
    private final int fetchBytes;
    private final int partitionBytes;
    private final int messageBytes;

    public AttachmentConversionKafkaConfiguration(
            @Value("${app.attachment-conversion.retry.technical-backoff}") Duration backoff,
            @Value("${app.attachment-conversion.kafka.fetch-max-bytes:0}") int fetchBytes,
            @Value("${app.attachment-conversion.kafka.partition-fetch-max-bytes:0}") int partitionBytes,
            @Value("${app.attachment-conversion.kafka.max-message-bytes:0}") int messageBytes) {
        Assert.isTrue(backoff != null && backoff.isPositive(),
                TripInternalErrorMessage.CONVERSION_TECHNICAL_BACKOFF_INVALID.message());
        this.backoff = backoff;
        this.fetchBytes = fetchBytes;
        this.partitionBytes = partitionBytes;
        this.messageBytes = messageBytes;
    }

    @Bean
    public ThreadPoolTaskScheduler attachmentConversionKafkaScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("attachment-conversion-kafka-");
        // A synchronous DLT metadata/buffer wait must not hold another topic's retry callback.
        scheduler.setPoolSize(4);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "app.attachment-conversion.mode", havingValue = "KAFKA")
    @ConditionalOnExpression("${app.attachment-conversion.consumer-enabled:true}")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> attachmentConversionKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers,
            @Lazy AttachmentConversionKafkaListener listener,
            @Qualifier("attachmentConversionKafkaScheduler") TaskScheduler scheduler, KafkaListenerEndpointRegistry registry) {
        Assert.isTrue(fetchBytes > 0 && partitionBytes > 0 && messageBytes > 0,
                TripInternalErrorMessage.CONVERSION_KAFKA_FETCH_OR_MESSAGE_SIZE_INVALID.message());
        DefaultKafkaConsumerFactory<Object, Object> isolated = isolatedConsumers(consumers);
        isolated.updateConfigs(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1,
                ConsumerConfig.FETCH_MAX_BYTES_CONFIG, fetchBytes, ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, partitionBytes));
        isolated.addPostProcessor(consumer -> CommitGate.wrap(consumer, backoff));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, isolated);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPauseImmediate(true);
        factory.getContainerProperties().setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
            }

            @Override
            public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                listener.onPartitionsLost(consumer, partitions);
            }

            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                listener.onPartitionsAssigned(consumer, partitions);
            }
        });
        factory.setCommonErrorHandler(errors(scheduler, registry));
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "app.attachment-conversion.receipt-listener-enabled", havingValue = "true", matchIfMissing = true)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> attachmentConversionReceiptKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers,
            @Qualifier("attachmentConversionKafkaScheduler") TaskScheduler scheduler, KafkaListenerEndpointRegistry registry) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, isolatedConsumers(consumers));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errors(scheduler, registry));
        return factory;
    }

    private DefaultKafkaConsumerFactory<Object, Object> isolatedConsumers(ConsumerFactory<Object, Object> consumers) {
        var settings = new HashMap<>(consumers.getConfigurationProperties());
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var isolated = new DefaultKafkaConsumerFactory<Object, Object>(settings,
                consumers::getKeyDeserializer, consumers::getValueDeserializer);
        consumers.getPostProcessors().forEach(isolated::addPostProcessor);
        return isolated;
    }

    private CommonErrorHandler errors(TaskScheduler scheduler, KafkaListenerEndpointRegistry registry) {
        var pauses = new ListenerContainerPauseService(registry, scheduler);
        return new CommonErrorHandler() {
            @Override
            public boolean isAckAfterHandle() {
                return false;
            }

            @Override
            public boolean handleOne(Exception failure, ConsumerRecord<?, ?> record,
                    Consumer<?, ?> consumer, MessageListenerContainer container) {
                handleOtherException(failure, consumer, container, false);
                return false;
            }

            @Override
            public void handleOtherException(Exception failure, Consumer<?, ?> consumer,
                    MessageListenerContainer container, boolean batchListener) {
                log.warn(TripInternalErrorMessage.CONVERSION_KAFKA_TECHNICAL_RETRY.message());
                pauses.pause(container, backoff);
            }
        };
    }

    // Spring's async ACK bookkeeping can resume after a failed sync commit; retain the offset gate until broker confirmation.
    private static final class CommitGate implements InvocationHandler {
        private final Consumer<Object, Object> consumer;
        private final long backoffNanos;
        private final Map<TopicPartition, OffsetAndMetadata> pending = new HashMap<>();
        private final Set<TopicPartition> requestedPauses = new HashSet<>();
        private Duration timeout;
        private long retryAt;

        private CommitGate(Consumer<Object, Object> consumer, Duration backoff) {
            this.consumer = consumer;
            this.backoffNanos = backoff.toNanos();
        }

        @SuppressWarnings("unchecked")
        private static Consumer<Object, Object> wrap(Consumer<Object, Object> consumer, Duration backoff) {
            return (Consumer<Object, Object>) Proxy.newProxyInstance(Consumer.class.getClassLoader(),
                    new Class<?>[]{Consumer.class}, new CommitGate(consumer, backoff));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "commitSync" -> {
                    if (args != null && args.length == 2 && args[0] instanceof Map<?, ?>) {
                        boolean wasEmpty = pending.isEmpty();
                        pending.putAll((Map<TopicPartition, OffsetAndMetadata>) args[0]);
                        timeout = (Duration) args[1];
                        if (wasEmpty) {
                            retryAt = System.nanoTime();
                        }
                        confirm();
                        return null;
                    }
                }
                case "poll" -> {
                    confirm();
                    if (!pending.isEmpty()) {
                        consumer.pause(consumer.assignment());
                    }
                }
                case "pause" -> requestedPauses.addAll((Collection<TopicPartition>) args[0]);
                case "resume" -> {
                    requestedPauses.removeAll((Collection<TopicPartition>) args[0]);
                    if (!pending.isEmpty()) {
                        return null;
                    }
                }
                case "subscribe" -> {
                    if (args.length == 2 && args[1] instanceof ConsumerRebalanceListener listener) {
                        args[1] = new ConsumerRebalanceListener() {
                            @Override
                            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                                forget(partitions);
                                try {
                                    listener.onPartitionsRevoked(partitions);
                                } finally {
                                    forget(partitions);
                                }
                            }

                            @Override
                            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                                listener.onPartitionsAssigned(partitions);
                            }

                            @Override
                            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                                forget(partitions);
                                try {
                                    listener.onPartitionsLost(partitions);
                                } finally {
                                    forget(partitions);
                                }
                            }
                        };
                    }
                }
            }
            try {
                return method.invoke(consumer, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private void forget(Collection<TopicPartition> partitions) {
            pending.keySet().removeAll(partitions);
            requestedPauses.removeAll(partitions);
        }

        private void confirm() {
            if (pending.isEmpty() || retryAt - System.nanoTime() > 0) {
                return;
            }
            try {
                consumer.commitSync(Map.copyOf(pending), timeout);
                pending.clear();
                var resumable = new HashSet<>(consumer.assignment());
                resumable.removeAll(requestedPauses);
                consumer.resume(resumable);
            } catch (RuntimeException failure) {
                retryAt = System.nanoTime() + backoffNanos;
                consumer.pause(consumer.assignment());
                log.warn(TripInternalErrorMessage.CONVERSION_KAFKA_COMMIT_RETRY.message());
            }
        }
    }
}
