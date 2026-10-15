package com.yeodam.yeodambe.integration.config;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

@Configuration(proxyBeanMethods = false)
public class OutboxReceiptKafkaConfiguration {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> outboxReceiptKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // 실패 레코드를 커밋하지 않고 중단한다. 원인 해결 후 같은 group으로 재시작한다.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
