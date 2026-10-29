package com.yeodam.yeodambe.trip.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.consumer.ConsumerConfig;

@Configuration(proxyBeanMethods = false)
public class MigrationTripKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> migrationTripListenerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @org.springframework.beans.factory.annotation.Value("${migration.trip.concurrency:1}") int concurrency
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        if (concurrency < 1 || concurrency > 4) throw new IllegalArgumentException("Invalid rehearsal concurrency");
        factory.setConcurrency(concurrency);
        factory.setMissingTopicsFatal(true);
        factory.setBatchListener(true);
        // Factory template consumer properties are not copied to every endpoint in 4.1.
        // Apply this bound to each created trip container, leaving auth consumers intact.
        factory.setContainerCustomizer(container -> {
            var properties = container.getContainerProperties().getKafkaConsumerProperties();
            properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");
            container.getContainerProperties().setKafkaConsumerProperties(properties);
        });
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setSyncCommits(true);
        // A poll contains at most ten records; finish its DB transactions and offset
        // commit before stop's callback allows the Cloud controller to fence MySQL.
        // No fetch-min-bytes or extra batch-fill wait is added.
        factory.getContainerProperties().setStopImmediate(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
