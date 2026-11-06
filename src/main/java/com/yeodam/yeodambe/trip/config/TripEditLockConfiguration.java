package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TripEditLockProperties.class)
public class TripEditLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    RedissonClient tripEditRedissonClient(
            RedisConnectionFactory factory,
            DataRedisProperties redisProperties,
            TripEditLockProperties lockProperties
    ) {
        if (!(factory instanceof LettuceConnectionFactory lettuce)
                || lettuce.getStandaloneConfiguration() == null) {
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_LOCK_REQUIRES_STANDALONE_REDIS.message());
        }
        RedisStandaloneConfiguration standalone = lettuce.getStandaloneConfiguration();
        String scheme = lettuce.getClientConfiguration().isUseSsl() ? "rediss://" : "redis://";
        Config config = new Config();
        config.setThreads(2);
        config.setNettyThreads(2);
        config.setLockWatchdogTimeout(lockProperties.watchdogTimeout().toMillis());
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + standalone.getHostName() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase())
                .setTimeout(Math.toIntExact(lettuce.getClientConfiguration().getCommandTimeout().toMillis()))
                .setConnectTimeout(Math.toIntExact(redisProperties.getConnectTimeout() == null
                        ? 2000 : redisProperties.getConnectTimeout().toMillis()))
                .setSubscriptionTimeout(1000)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(32);
        if (standalone.getUsername() != null) {
            server.setUsername(standalone.getUsername());
        }
        if (standalone.getPassword().isPresent()) {
            server.setPassword(new String(standalone.getPassword().get()));
        }
        return Redisson.create(config);
    }
}
