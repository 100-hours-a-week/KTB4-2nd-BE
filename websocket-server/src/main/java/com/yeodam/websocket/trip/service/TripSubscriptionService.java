package com.yeodam.websocket.trip.service;

import lombok.extern.slf4j.Slf4j;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TripSubscriptionService implements ExecutorChannelInterceptor {

    private static final Pattern TRIP_SUBSCRIPTION = Pattern.compile("/topic/trips/([1-9][0-9]*)/events");
    private static final Pattern TRIP_COMMAND = Pattern.compile("/app/trips/([1-9][0-9]*)/(update|attachments/delete)");

    private final BackendTripEditClient client;
    private final WebSocketSessionManager sessionManager;
    private final TaskScheduler scheduler;
    private final ThreadPoolTaskExecutor executor;
    private final Map<AccessKey, RevalidationRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AccessKey> pending = new ConcurrentLinkedDeque<>();
    private volatile boolean stopping;
    private final Map<SubscriptionKey, Subscription> subscriptions = new ConcurrentHashMap<>();

    public TripSubscriptionService(
            BackendTripEditClient client,
            WebSocketSessionManager sessionManager,
            @Qualifier("tripSubscriptionRevalidationTaskScheduler") TaskScheduler scheduler,
            @Qualifier("tripSubscriptionRevalidationExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.client = client;
        this.sessionManager = sessionManager;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    public boolean authorize(Message<?> message, Authentication authentication) {
        if (stopping || authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof WebSocketPrincipal principal)) {
            return false;
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String destination = headers.getDestination();
        Pattern pattern = headers.getMessageType() == SimpMessageType.SUBSCRIBE
                ? TRIP_SUBSCRIPTION : TRIP_COMMAND;
        Matcher matcher = pattern.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) {
            return false;
        }
        try {
            long tripId = Long.parseLong(matcher.group(1));
            if (headers.getMessageType() == SimpMessageType.MESSAGE) {
                return true;
            }
            Map<String, Object> attributes = headers.getSessionAttributes();
            if (attributes == null || headers.getSessionId() == null || headers.getSubscriptionId() == null
                    || !(attributes.get(WebSocketSessionCredentials.ATTRIBUTE_NAME)
                    instanceof WebSocketSessionCredentials credentials)) {
                return false;
            }
            Instant validUntil = client.checkAccess(tripId, principal.userId(), credentials);
            subscriptions.put(new SubscriptionKey(headers.getSessionId(), headers.getSubscriptionId()),
                    new Subscription(headers.getSessionId(), tripId, principal.userId(), credentials, validUntil));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Scheduled(scheduler = "tripSubscriptionRevalidationTaskScheduler", initialDelay = 30_000, fixedDelay = 30_000)
    public void revalidateSubscriptions() {
        if (stopping) {
            return;
        }
        pruneInactiveSubscriptions();
        Map<AccessKey, List<SubscriptionSnapshot>> groups = subscriptions.entrySet().stream()
                .map(entry -> new SubscriptionSnapshot(entry.getKey(), entry.getValue()))
                .collect(Collectors.groupingBy(snapshot -> accessKey(snapshot.subscription())));
        groups.forEach((key, affected) -> enqueue(key, affected, false));
        startWorkers();
    }

    private void pruneInactiveSubscriptions() {
        Map<SubscriptionKey, Subscription> previousSubscriptions = Map.copyOf(subscriptions);
        Set<String> activeSessions = sessionManager.getSessions().stream()
                .map(org.springframework.web.socket.WebSocketSession::getId)
                .collect(Collectors.toSet());
        previousSubscriptions.forEach((key, subscription) -> {
            if (!activeSessions.contains(key.sessionId())) {
                subscriptions.computeIfPresent(key, (ignored, current) -> current == subscription ? null : current);
            }
        });
    }

    @EventListener
    public void onPermissionChanged(TripPermissionChanged event) {
        scheduler.schedule(() -> revalidate(event.userId(), event.tripId()), Instant.now());
    }

    public void revalidate(long userId, long tripId) {
        List<SubscriptionSnapshot> affected = subscriptions.entrySet().stream()
                .filter(entry -> entry.getValue().userId() == userId && entry.getValue().tripId() == tripId)
                .map(entry -> new SubscriptionSnapshot(entry.getKey(), entry.getValue()))
                .toList();
        if (!affected.isEmpty()) {
            enqueue(new AccessKey(userId, tripId), affected, true);
            startWorkers();
        }
    }

    private void enqueue(AccessKey key, List<SubscriptionSnapshot> affected, boolean permissionChanged) {
        if (stopping) {
            return;
        }
        RevalidationRequest request = new RevalidationRequest(affected);
        RevalidationRequest previous = permissionChanged
                ? requests.put(key, request) : requests.putIfAbsent(key, request);
        if (previous == null) {
            pending.addLast(key);
        }
    }

    private void startWorkers() {
        for (int worker = 0; worker < executor.getMaxPoolSize() && !pending.isEmpty() && !stopping; worker++) {
            try {
                executor.execute(this::drainPending);
            } catch (RejectedExecutionException exception) {
                log.atDebug().addKeyValue("event", "trip_subscription_revalidation")
                        .addKeyValue("failure_stage", "dispatch")
                        .addKeyValue("cause_type", exception.getClass().getSimpleName())
                        .log("Permission workers are occupied; pending checks remain queued.");
                return;
            }
        }
    }

    private void drainPending() {
        AccessKey key;
        while (!stopping && (key = pending.pollFirst()) != null) {
            RevalidationRequest request = requests.get(key);
            if (request == null) {
                continue;
            }
            try {
                checkPermission(key, request);
            } finally {
                finishCheck(key, request);
            }
        }
    }

    private void finishCheck(AccessKey key, RevalidationRequest completed) {
        RevalidationRequest next = requests.compute(key,
                (ignored, current) -> current == completed ? null : current);
        if (next != null && !stopping) {
            pending.addFirst(key);
        }
    }

    private void checkPermission(AccessKey key, RevalidationRequest request) {
        List<SubscriptionSnapshot> current = request.subscriptions().stream()
                .map(snapshot -> new SubscriptionSnapshot(snapshot.key(), subscriptions.get(snapshot.key())))
                .filter(snapshot -> snapshot.subscription() != null && accessKey(snapshot.subscription()).equals(key))
                .toList();
        if (current.isEmpty()) {
            return;
        }
        Subscription selected = current.getFirst().subscription();
        try {
            Instant validUntil = client.checkAccess(selected.tripId(), selected.userId(), selected.credentials());
            if (requests.get(key) != request || stopping) {
                return;
            }
            current.forEach(snapshot -> {
                Subscription subscription = snapshot.subscription();
                subscriptions.computeIfPresent(snapshot.key(), (ignored, stored) -> stored == subscription
                        ? new Subscription(subscription.sessionId(), subscription.tripId(), subscription.userId(),
                                subscription.credentials(), validUntil)
                        : stored);
            });
        } catch (RuntimeException exception) {
            if (stopping) {
                return;
            }
            log.atWarn().addKeyValue("event", "trip_subscription_revalidation")
                    .addKeyValue("trip_id", selected.tripId())
                    .addKeyValue("user_id", selected.userId())
                    .addKeyValue("cause_type", exception.getClass().getSimpleName())
                    .log("Trip subscription validation failed; closing affected connections.");
            List<String> affectedSessionIds = current.stream()
                    .filter(snapshot -> snapshot.subscription() == subscriptions.get(snapshot.key()))
                    .map(snapshot -> snapshot.subscription().sessionId()).distinct().toList();
            affectedSessionIds.forEach(sessionId -> {
                removeSession(sessionId);
                sessionManager.closeConnection(sessionId);
            });
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return authorizeDelivery(message);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return authorizeDelivery(message);
    }

    private Message<?> authorizeDelivery(Message<?> message) {
        if (stopping) {
            return null;
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String destination = headers.getDestination();
        if (headers.getMessageType() != SimpMessageType.MESSAGE || destination == null
                || !TRIP_SUBSCRIPTION.matcher(destination).matches()) {
            return message;
        }
        if (headers.getSessionId() == null) {
            return null;
        }
        Subscription subscription = subscriptions.get(new SubscriptionKey(headers.getSessionId(), headers.getSubscriptionId()));
        if (subscription == null || !destination.equals("/topic/trips/" + subscription.tripId() + "/events")) {
            return null;
        }
        if (subscription.validUntil() == null || !subscription.validUntil().isAfter(Instant.now())) {
            removeSession(headers.getSessionId());
            sessionManager.closeConnection(headers.getSessionId());
            return null;
        }
        return message;
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        subscriptions.remove(new SubscriptionKey(headers.getSessionId(), headers.getSubscriptionId()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        removeSession(event.getSessionId());
    }

    private void removeSession(String sessionId) {
        subscriptions.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @PreDestroy
    public void stopRevalidation() {
        stopping = true;
        pending.clear();
        requests.clear();
        subscriptions.clear();
    }

    private record RevalidationRequest(
            List<SubscriptionSnapshot> subscriptions
    ) {
    }

    private AccessKey accessKey(Subscription subscription) {
        return new AccessKey(subscription.userId(), subscription.tripId());
    }

    private record SubscriptionSnapshot(
            SubscriptionKey key,
            Subscription subscription
    ) {
    }

    private record SubscriptionKey(
            String sessionId,
            String subscriptionId
    ) {
    }

    private record AccessKey(
            long userId,
            long tripId
    ) {
    }

    private record Subscription(
            String sessionId,
            long tripId,
            long userId,
            WebSocketSessionCredentials credentials,
            Instant validUntil
    ) {
    }
}
