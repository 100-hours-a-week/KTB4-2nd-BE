package com.yeodam.yeodambe.integration.config;

import com.yeodam.yeodambe.integration.service.KafkaDltLogService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@RequiredArgsConstructor
@Configuration(proxyBeanMethods = false)
public class TripCreationKafkaConfiguration {

    private final KafkaDltLogService dltLogs;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
    tripUpdateResultKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafka,
            @Value("${app.kafka.dead-letter-topics.trip-update-result}") String deadLetterTopic
    ) {
        return tripResultKafkaListenerContainerFactory(configurer, consumerFactory, kafka, deadLetterTopic);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
    tripResultKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafka,
            @Value("${app.kafka.dead-letter-topics.trip-create-result}") String deadLetterTopic
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        var recoverer = new DeadLetterPublishingRecoverer(
                kafka,
                (record, failure) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        ConsumerAwareRecordRecoverer loggingRecoverer = (record, consumer, failure) -> {
            recoverer.accept(record, consumer, failure);
            dltLogs.logPublished(record, failure, deadLetterTopic);
        };
        var errors = new DefaultErrorHandler(
                loggingRecoverer,
                new FixedBackOff(1_000L, 3L)
        );
        errors.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
