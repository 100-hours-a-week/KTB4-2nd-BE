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
        // Factory ContainerProperties are not copied for this setting in Spring Kafka 4.1.
        // The listener supplies endpoint properties; validate the generated container itself.
        factory.setContainerCustomizer(container -> {
            var properties = container.getContainerProperties().getKafkaConsumerProperties();
            if (!"32".equals(properties.getProperty("max.poll.records"))
                    || !"false".equals(properties.getProperty("enable.auto.commit"))) {
                throw new IllegalStateException("Migration trip consumer requires max.poll.records=32 and enable.auto.commit=false");
            }
            org.slf4j.LoggerFactory.getLogger(MigrationTripKafkaConfig.class).info(
                    "migration_trip_consumer_config max_poll_records=32 enable_auto_commit=false concurrency={}", concurrency);
        });
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
