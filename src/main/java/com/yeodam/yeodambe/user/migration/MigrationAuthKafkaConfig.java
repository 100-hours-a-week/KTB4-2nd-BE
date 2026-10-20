package com.yeodam.yeodambe.user.migration;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="migration.auth.enabled",havingValue="true")
public class MigrationAuthKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String,String> migrationAuthListenerFactory(ConsumerFactory<String,String> cf) {
        var f=new ConcurrentKafkaListenerContainerFactory<String,String>();f.setConsumerFactory(cf);f.setConcurrency(1);
        f.setMissingTopicsFatal(true);f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L,FixedBackOff.UNLIMITED_ATTEMPTS)));
        return f;
    }
}
