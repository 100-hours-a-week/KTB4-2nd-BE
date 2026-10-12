package com.yeodam.yeodambe.story.config;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

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
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        var factory =
                new ConcurrentKafkaListenerContainerFactory<Object, Object>();

        configurer.configure(factory, consumerFactory);

        var errorHandler = new DefaultErrorHandler(
                new FixedBackOff(
                        1_000L,
                        FixedBackOff.UNLIMITED_ATTEMPTS
                )
        );

        errorHandler.setClassifications(
                Map.of(Exception.class, true),
                true
        );

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}