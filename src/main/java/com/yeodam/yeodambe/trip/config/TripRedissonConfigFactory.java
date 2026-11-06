package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SslVerificationMode;
import org.redisson.config.SubscriptionMode;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

final class TripRedissonConfigFactory {

    private TripRedissonConfigFactory() {
    }

    static Config createConnectionConfig(RedisConnectionFactory factory, DataRedisProperties properties) {
        if (!(factory instanceof LettuceConnectionFactory lettuce) || lettuce.isClusterAware()) {
            throw new IllegalStateException(TripInternalErrorMessage.REDIS_LOCK_CONFIGURATION_UNSUPPORTED.message());
        }
        Config config = new Config();
        config.setSslVerificationMode(SslVerificationMode.STRICT);
        String scheme = lettuce.getClientConfiguration().isUseSsl() ? "rediss" : "redis";
        BaseConfig<?> server;
        RedisSentinelConfiguration sentinel = lettuce.getSentinelConfiguration();
        if (sentinel != null) {
            server = configureSentinel(config, sentinel, scheme);
        } else {
            RedisStandaloneConfiguration standalone = lettuce.getStandaloneConfiguration();
            config.setUsername(standalone.getUsername());
            if (standalone.getPassword().isPresent()) {
                config.setPassword(new String(standalone.getPassword().get()));
            }
            server = config.useSingleServer()
                    .setAddress(address(scheme, standalone.getHostName(), standalone.getPort()))
                    .setDatabase(standalone.getDatabase());
        }
        Duration connectTimeout = properties.getConnectTimeout() == null
                ? Duration.ofSeconds(2) : properties.getConnectTimeout();
        server.setTimeout(milliseconds(lettuce.getClientConfiguration().getCommandTimeout()));
        server.setConnectTimeout(milliseconds(connectTimeout));
        return config;
    }

    private static SentinelServersConfig configureSentinel(
            Config config,
            RedisSentinelConfiguration sentinel,
            String scheme
    ) {
        if (sentinel.getMaster() == null || !StringUtils.hasText(sentinel.getMaster().getName())
                || sentinel.getSentinels().isEmpty()) {
            throw new IllegalStateException(TripInternalErrorMessage.REDIS_LOCK_SENTINEL_CONFIGURATION_INVALID.message());
        }
        config.setUsername(sentinel.getUsername());
        if (sentinel.getPassword().isPresent()) {
            config.setPassword(new String(sentinel.getPassword().get()));
        }
        SentinelServersConfig server = config.useSentinelServers()
                .setMasterName(sentinel.getMaster().getName())
                .setDatabase(sentinel.getDatabase())
                .setSentinelUsername(sentinel.getSentinelUsername())
                .setReadMode(ReadMode.MASTER)
                .setSubscriptionMode(SubscriptionMode.MASTER);
        if (sentinel.getSentinelPassword().isPresent()) {
            server.setSentinelPassword(new String(sentinel.getSentinelPassword().get()));
        }
        String[] addresses = sentinel.getSentinels().stream()
                .map(node -> address(scheme, node.getHost(), node.getPort()))
                .toArray(String[]::new);
        server.addSentinelAddress(addresses);
        return server;
    }

    static void applyServerSettings(Config config, SingleServerConfig settings) {
        BaseConfig<?> server;
        if (config.isSentinelConfig()) {
            SentinelServersConfig sentinel = config.useSentinelServers();
            sentinel.setMasterConnectionMinimumIdleSize(settings.getConnectionMinimumIdleSize());
            sentinel.setMasterConnectionPoolSize(settings.getConnectionPoolSize());
            sentinel.setSlaveConnectionMinimumIdleSize(settings.getConnectionMinimumIdleSize());
            sentinel.setSlaveConnectionPoolSize(settings.getConnectionPoolSize());
            sentinel.setSubscriptionConnectionMinimumIdleSize(settings.getSubscriptionConnectionMinimumIdleSize());
            sentinel.setSubscriptionConnectionPoolSize(settings.getSubscriptionConnectionPoolSize());
            server = sentinel;
        } else {
            SingleServerConfig single = config.useSingleServer();
            single.setConnectionMinimumIdleSize(settings.getConnectionMinimumIdleSize());
            single.setConnectionPoolSize(settings.getConnectionPoolSize());
            single.setSubscriptionConnectionMinimumIdleSize(settings.getSubscriptionConnectionMinimumIdleSize());
            single.setSubscriptionConnectionPoolSize(settings.getSubscriptionConnectionPoolSize());
            server = single;
        }
        server.setSubscriptionTimeout(settings.getSubscriptionTimeout());
        server.setRetryAttempts(settings.getRetryAttempts());
    }

    private static int milliseconds(Duration value) {
        if (value == null || value.isNegative()
                || value.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0 || value.toMillis() == 0L) {
            throw new IllegalArgumentException(TripInternalErrorMessage.REDIS_LOCK_TIMEOUT_INVALID.message());
        }
        return Math.toIntExact(value.toMillis());
    }

    private static String address(String scheme, String host, Integer port) {
        if (!StringUtils.hasText(host) || port == null || port <= 0 || port > 65535) {
            throw new IllegalStateException(TripInternalErrorMessage.REDIS_LOCK_SENTINEL_CONFIGURATION_INVALID.message());
        }
        try {
            return new URI(scheme, null, host, port, null, null, null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.REDIS_LOCK_SENTINEL_CONFIGURATION_INVALID.message());
        }
    }
}
