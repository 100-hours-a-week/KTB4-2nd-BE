package com.yeodam.websocket.trip.config;

import com.yeodam.websocket.trip.exception.TripPresenceException;
import com.yeodam.websocket.trip.service.TripPresenceService;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TripPresenceProperties.class)
public class TripPresenceMessagingConfig implements WebSocketMessageBrokerConfigurer {
    private final TripPresenceService service;

    public TripPresenceMessagingConfig(@Lazy TripPresenceService service) {
        this.service = service;
    }

    @Bean
    static BeanPostProcessor tripPresenceInboundOrdering() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("clientInboundChannel".equals(beanName) && bean instanceof ExecutorSubscribableChannel channel) {
                    OrderedMessageChannelDecorator.configureInterceptor(channel, true);
                }
                return bean;
            }
        };
    }

    @Bean
    ThreadPoolTaskScheduler tripPresenceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("trip-presence-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    ThreadPoolTaskScheduler tripPresenceAccessScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("trip-presence-access-scheduler-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskExecutor tripPresenceAccessExecutor() {
        return executor("trip-presence-access-", 16, 0);
    }

    @Bean
    ThreadPoolTaskExecutor tripPresenceCleanupExecutor() {
        return executor("trip-presence-cleanup-", 4, 1000);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int workers, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        return executor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ExecutorChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
                if (headers.getCommand() == StompCommand.UNSUBSCRIBE) {
                    service.unsubscribe(headers.getSessionId(), headers.getSubscriptionId());
                }
                return orderPresenceCommand(message, channel, headers);
            }

            @Override
            public void afterSendCompletion(
                    Message<?> message, MessageChannel channel, boolean sent, Exception failure
            ) {
                StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
                if (!sent || failure != null) {
                    PendingCommand command = (PendingCommand) headers.getHeader("presencePendingCommand");
                    if (command != null) {
                        command.release();
                    }
                    if (headers.getCommand() == StompCommand.SUBSCRIBE) {
                        service.unsubscribe(headers.getSessionId(), headers.getSubscriptionId());
                    }
                    if (failure != null && command != null) {
                        service.rejectCommand(message, failure);
                    }
                }
            }

            @Override
            public void afterMessageHandled(
                    Message<?> message, MessageChannel channel, MessageHandler handler, Exception failure
            ) {
                PendingCommand command = (PendingCommand) message.getHeaders().get("presencePendingCommand");
                if (command != null && command.handlers.decrementAndGet() == 0) {
                    command.release();
                }
            }
        });
    }

    private Message<?> orderPresenceCommand(
            Message<?> message, MessageChannel channel, StompHeaderAccessor headers
    ) {
        String destination = headers.getDestination();
        if (headers.getCommand() != StompCommand.SEND || destination == null
                || !destination.matches("/app/trips/[^/]+/presence/(join|heartbeat|leave)")) {
            return message;
        }
        PendingCommand pending = (PendingCommand) headers.getHeader("presencePendingCommand");
        if (pending != null) {
            return pending.queue.closed() ? null : message;
        }
        Map<String, Object> attributes = headers.getSessionAttributes();
        if (attributes == null) {
            return message;
        }
        PresenceCommandQueue queue;
        synchronized (attributes) {
            queue = (PresenceCommandQueue) attributes.computeIfAbsent(
                    TripPresenceMessagingConfig.class.getName(), key -> new PresenceCommandQueue(channel));
        }
        try {
            if (!queue.admit()) {
                return null;
            }
        } catch (TripPresenceException exception) {
            service.rejectCommand(message, exception);
            return null;
        }
        headers.setHeader("presencePendingCommand", new PendingCommand(queue,
                ((ExecutorSubscribableChannel) channel).getSubscribers().size()));
        headers.setLeaveMutable(true);
        queue.ordered.send(MessageBuilder.createMessage(message.getPayload(), headers.getMessageHeaders()));
        return null;
    }

    private static final class PresenceCommandQueue {
        private static final int MAX_PENDING_COMMANDS = 10;
        private final OrderedMessageChannelDecorator ordered;
        private int pending;
        private boolean closed;

        private PresenceCommandQueue(MessageChannel channel) {
            ordered = new OrderedMessageChannelDecorator(channel, LogFactory.getLog(TripPresenceMessagingConfig.class));
        }

        private synchronized boolean admit() {
            if (closed) {
                return false;
            }
            if (pending >= MAX_PENDING_COMMANDS) {
                closed = true;
                throw new TripPresenceException("PRESENCE_LIMIT_EXCEEDED");
            }
            pending++;
            return true;
        }

        private synchronized void release() {
            pending--;
        }

        private synchronized boolean closed() {
            return closed;
        }
    }

    private static final class PendingCommand {
        private final PresenceCommandQueue queue;
        private final AtomicInteger handlers;
        private final AtomicBoolean released = new AtomicBoolean();

        private PendingCommand(PresenceCommandQueue queue, int handlers) {
            this.queue = queue;
            this.handlers = new AtomicInteger(handlers);
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                queue.release();
            }
        }
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ExecutorChannelInterceptor() {
            @Override
            public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                return service.canDeliver(message) ? message : null;
            }
        });
    }
}
