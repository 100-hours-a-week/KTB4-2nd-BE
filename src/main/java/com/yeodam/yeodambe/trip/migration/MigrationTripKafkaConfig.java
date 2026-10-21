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
        factory.setBatchListener(true);
        var properties=new java.util.Properties();
        properties.setProperty("max.poll.records","32");
        properties.setProperty("enable.auto.commit","false");
        factory.getContainerProperties().setKafkaConsumerProperties(properties);
        factory.setMissingTopicsFatal(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // The poll thread commits only completed prefixes; fencing cancels queued work.
        // No framework batch/auto acknowledgement may advance past an unfinished record.
        factory.getContainerProperties().setStopImmediate(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
