package com.yeodam.yeodambe.integration.config;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("msk")
public class MskKafkaConfiguration {

    public MskKafkaConfiguration(@Value("${KAFKA_BOOTSTRAP_SERVERS:}") String bootstrapServers) {
        Assert.hasText(bootstrapServers, IntegrationInternalErrorMessage.MSK_BOOTSTRAP_SERVERS_BLANK.message());
    }
}
