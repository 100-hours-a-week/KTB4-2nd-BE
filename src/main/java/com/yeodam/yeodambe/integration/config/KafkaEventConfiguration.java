package com.yeodam.yeodambe.integration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaEventConfiguration {
}
