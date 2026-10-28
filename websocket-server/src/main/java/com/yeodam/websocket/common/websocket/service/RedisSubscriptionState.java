package com.yeodam.websocket.common.websocket.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisSubscriptionState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public boolean isReady() {
        return ready.get();
    }

    public void markSubscribed() {
        ready.set(true);
    }

    public boolean markUnavailable() {
        return ready.getAndSet(false);
    }
}