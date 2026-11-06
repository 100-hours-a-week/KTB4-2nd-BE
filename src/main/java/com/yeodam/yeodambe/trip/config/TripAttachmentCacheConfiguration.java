package com.yeodam.yeodambe.trip.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
public class TripAttachmentCacheConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    RedissonClient photoListRedissonClient(RedisConnectionFactory factory, DataRedisProperties properties) {
        Config config = TripRedissonConfigFactory.createConnectionConfig(factory, properties);
        config.setThreads(2);
        config.setNettyThreads(2);
        SingleServerConfig settings = new Config().useSingleServer()
                .setSubscriptionTimeout(1000)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(32);
        TripRedissonConfigFactory.applyServerSettings(config, settings);
        return Redisson.create(config);
    }
}
