package com.yeodam.yeodambe.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.attachment-conversion.operations.enabled", havingValue = "false", matchIfMissing = true)
public class SchedulingConfiguration {
}
