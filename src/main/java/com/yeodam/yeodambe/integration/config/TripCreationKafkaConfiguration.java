package com.yeodam.yeodambe.integration.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
public class TripCreationKafkaConfiguration {
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
        var recoverer = new DeadLetterPublishingRecoverer(
                kafka,
                (record, failure) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        var errors = new DefaultErrorHandler(
                recoverer, new FixedBackOff(1000L,
                        FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
