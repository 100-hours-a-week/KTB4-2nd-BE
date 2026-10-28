package com.yeodam.yeodambe.common.websocket.service;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.config.WebSocketEventProperties;
import com.yeodam.yeodambe.common.websocket.service.request.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WebSocketEventPublisherRedisTest {

    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) connections.destroy();
    }

    @Test
    void twoPublisherInstancesReachBothRedisSubscribers() throws Exception {
        String channel = "yeodam:test-" + UUID.randomUUID() + ":websocket:events:v1";
        ObjectMapper mapper = new ObjectMapper();
        var properties = new WebSocketEventProperties(channel);
        var firstPublisher = new WebSocketEventPublisher(redis, mapper, properties);
        var secondPublisher = new WebSocketEventPublisher(redis, mapper, properties);
        var firstReceiver = new Receiver();
        var secondReceiver = new Receiver();
        var firstSubscriber = subscriber(channel, firstReceiver);
        var secondSubscriber = subscriber(channel, secondReceiver);
        try {
            firstSubscriber.start();
            secondSubscriber.start();
            assertThat(firstReceiver.subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondReceiver.subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            WebSocketEvent first = event("first");
            WebSocketEvent second = event("second");

            firstPublisher.publish(first);
            secondPublisher.publish(second);

            for (Receiver receiver : new Receiver[]{firstReceiver, secondReceiver}) {
                WebSocketEvent firstReceived = mapper.readValue(receiver.next(), WebSocketEvent.class);
                WebSocketEvent secondReceived = mapper.readValue(receiver.next(), WebSocketEvent.class);
                assertThat(List.of(firstReceived, secondReceived))
                        .containsExactlyInAnyOrder(first, second);
            }
        } finally {
            firstSubscriber.destroy();
            secondSubscriber.destroy();
        }
    }

    private RedisMessageListenerContainer subscriber(String channel, Receiver receiver) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(receiver, new ChannelTopic(channel));
        container.afterPropertiesSet();
        return container;
    }

    private WebSocketEvent event(String source) {
        return new WebSocketEvent(UUID.randomUUID(), WebSocketEventType.AI_TASK, 1, Instant.now(),
                new WebSocketEventTarget(WebSocketEventTargetType.USER, 42L),
                new ApiResponse<>("TEST_EVENT", Map.of("source", source)));
    }

    private static class Receiver implements MessageListener, SubscriptionListener {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void onChannelSubscribed(byte[] channel, long count) {
            subscribed.countDown();
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            messages.add(new String(message.getBody(), StandardCharsets.UTF_8));
        }

        private String next() throws InterruptedException {
            String json = messages.poll(5, TimeUnit.SECONDS);
            assertThat(json).isNotNull();
            return json;
        }
    }
}
