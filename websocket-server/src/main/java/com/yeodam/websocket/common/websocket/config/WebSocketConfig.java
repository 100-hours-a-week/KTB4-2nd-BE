package com.yeodam.websocket.common.websocket.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionTrackingDecorator;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketProperties properties;
    private final TaskScheduler messageBrokerTaskScheduler;
    private final WebSocketSessionManager sessionManager;

    public WebSocketConfig(
            WebSocketProperties properties,
            @Lazy @Qualifier("messageBrokerTaskScheduler")
            TaskScheduler messageBrokerTaskScheduler,
            WebSocketSessionManager sessionManager
    ) {
        this.properties = properties;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
        this.sessionManager = sessionManager;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                        properties.allowedOrigins().toArray(String[]::new)
                );
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");

        registry.enableSimpleBroker("/queue/", "/topic/")
                .setTaskScheduler(messageBrokerTaskScheduler)
                .setHeartbeatValue(new long[]{10_000, 10_000});
    }

    @Override
    public void configureWebSocketTransport(
            WebSocketTransportRegistration registry
    ) {
        registry.setMessageSizeLimit(64 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(10_000);

        registry.addDecoratorFactory(handler ->
                new WebSocketSessionTrackingDecorator(handler, sessionManager)
        );
    }
}