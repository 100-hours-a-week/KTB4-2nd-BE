package com.yeodam.websocket.common.websocket.metrics;

import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

@Component
public class WebSocketMetrics implements MeterBinder {

    private final WebSocketSessionManager sessionManager;
    private final SimpUserRegistry userRegistry;

    public WebSocketMetrics(
            WebSocketSessionManager sessionManager,
            SimpUserRegistry userRegistry
    ) {
        this.sessionManager = sessionManager;
        this.userRegistry = userRegistry;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                "websocket.connections",
                sessionManager,
                manager -> manager.getSessions().size()
        ).register(registry);

        Gauge.builder(
                "websocket.subscriptions",
                userRegistry,
                users -> users.getUsers().stream()
                        .flatMap(user -> user.getSessions().stream())
                        .mapToInt(session -> session.getSubscriptions().size())
                        .sum()
        ).register(registry);
    }

}
