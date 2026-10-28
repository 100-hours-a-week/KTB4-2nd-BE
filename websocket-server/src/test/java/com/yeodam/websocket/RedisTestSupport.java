package com.yeodam.websocket;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

public abstract class RedisTestSupport {

    private static final GenericContainer<?> REDIS = startRedis();

    private static GenericContainer<?> startRedis() {
        var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        redis.start();
        return redis;
    }

    @DynamicPropertySource
    static void redisSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestSupport::redisHost);
        registry.add("spring.data.redis.port", RedisTestSupport::redisPort);
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
    }

    protected static String redisHost() { return REDIS.getHost(); }
    protected static int redisPort() { return REDIS.getMappedPort(6379); }
}
