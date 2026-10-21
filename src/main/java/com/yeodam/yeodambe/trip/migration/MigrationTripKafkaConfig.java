package com.yeodam.yeodambe.trip.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class MigrationTripKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> migrationTripListenerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @org.springframework.beans.factory.annotation.Value("${migration.trip.concurrency:1}") int concurrency
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("Invalid rehearsal concurrency");
        factory.setConcurrency(concurrency);
        factory.setMissingTopicsFatal(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Finish the current record and commit it; leave fetched but unapplied records
        // in Kafka for the Target instead of draining an entire poll during cutover.
        factory.getContainerProperties().setStopImmediate(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
