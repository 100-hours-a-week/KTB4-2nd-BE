package com.yeodam.websocket.common.websocket.config;

import com.yeodam.websocket.trip.exception.TripPresenceException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import java.nio.charset.StandardCharsets;
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
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentialsHandshakeInterceptor;

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
        registry.setErrorHandler(presenceErrorHandler());
        registry.addEndpoint("/ws")
                .addInterceptors(
                        new WebSocketSessionCredentialsHandshakeInterceptor()
                )
                .setAllowedOrigins(
                        properties.allowedOrigins().toArray(String[]::new)
                );
    }

    private StompSubProtocolErrorHandler presenceErrorHandler() {
        return new StompSubProtocolErrorHandler() {
            @Override
            public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> message, Throwable failure) {
                Throwable current = failure;
                while (current != null) {
                    if (current instanceof TripPresenceException presenceFailure) {
                        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
                        error.setLeaveMutable(true);
                        String body = "{\"message\":\"" + presenceFailure.getMessage() + "\",\"data\":null}";
                        return MessageBuilder.createMessage(body.getBytes(StandardCharsets.UTF_8),
                                error.getMessageHeaders());
                    }
                    current = current.getCause();
                }
                return super.handleClientMessageProcessingError(message, failure);
            }
        };
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