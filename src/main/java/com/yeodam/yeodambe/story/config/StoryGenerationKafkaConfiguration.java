package com.yeodam.yeodambe.story.config;

import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.service.StoryGenerationResultRecoveryService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.story.result-listener-enabled",
        havingValue = "true"
)
public class StoryGenerationKafkaConfiguration {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
    storyResultKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            StoryGenerationResultRecoveryService recoveryService,
            @Value("${app.story.result-dead-letter-topic}") String deadLetterTopic
    ) {
        var factory =
                new ConcurrentKafkaListenerContainerFactory<Object, Object>();

        configurer.configure(factory, consumerFactory);

        var publisher = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, failure) -> new TopicPartition(deadLetterTopic, -1)
        );
        publisher.setFailIfSendResultIsError(true);
        var errorHandler = new DefaultErrorHandler(
                (record, failure) -> {
                    publisher.accept(record, failure);
                    recoveryService.failIdentifiedJob((String) record.value());
                    log.error("Story result moved to DLT. topic={}, partition={}, offset={}, dlt={}",
                            record.topic(), record.partition(), record.offset(), deadLetterTopic, failure);
                },
                new FixedBackOff(1_000L, 3L)
        );
        errorHandler.addNotRetryableExceptions(InvalidStoryResultException.class);

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}