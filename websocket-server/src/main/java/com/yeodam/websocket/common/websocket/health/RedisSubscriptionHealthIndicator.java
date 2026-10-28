package com.yeodam.websocket.common.websocket.health;

import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class RedisSubscriptionHealthIndicator implements HealthIndicator {

    private final RedisSubscriptionState subscriptionState;

    public RedisSubscriptionHealthIndicator(
            RedisSubscriptionState subscriptionState
    ) {
        this.subscriptionState = subscriptionState;
    }

    @Override
    public Health health() {
        return subscriptionState.isReady()
                ? Health.up().build()
                : Health.down().build();
    }
}