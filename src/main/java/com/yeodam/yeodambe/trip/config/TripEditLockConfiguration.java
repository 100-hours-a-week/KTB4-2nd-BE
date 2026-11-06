package com.yeodam.yeodambe.trip.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.config.SslVerificationMode;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TripEditLockProperties.class)
public class TripEditLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    RedissonClient tripEditRedissonClient(
            RedisConnectionFactory factory,
            DataRedisProperties redisProperties,
            TripEditLockProperties lockProperties,
            Environment environment
    ) {
        return Redisson.create(createConfig(factory, redisProperties, lockProperties, environment));
    }

    Config createConfig(
            RedisConnectionFactory factory,
            DataRedisProperties redisProperties,
            TripEditLockProperties lockProperties,
            Environment environment
    ) {
        Config config = TripRedissonConfigFactory.createConnectionConfig(factory, redisProperties);
        Binder binder = Binder.get(environment);
        binder.bind("app.trip.edit-lock.client", Bindable.ofInstance(config));
        config.setLockWatchdogTimeout(lockProperties.watchdogTimeout().toMillis());
        BaseConfig<?> server;
        if (config.isSentinelConfig()) {
            SingleServerConfig settings = new Config().useSingleServer();
            binder.bind("app.trip.edit-lock.client.server", Bindable.ofInstance(settings));
            TripRedissonConfigFactory.applyServerSettings(config, settings);
            server = config.useSentinelServers();
        } else {
            server = config.useSingleServer();
        }
        binder.bind("app.trip.edit-lock.client.server", Bindable.ofInstance(server));
        config.setSslVerificationMode(SslVerificationMode.STRICT);
        server.setSslVerificationMode(SslVerificationMode.STRICT);
        return config;
    }
}
