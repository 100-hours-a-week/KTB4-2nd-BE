package com.yeodam.websocket.trip.service;

import com.yeodam.websocket.common.response.ErrorMessage;
import com.yeodam.websocket.common.response.SuccessMessage;
import lombok.extern.slf4j.Slf4j;
import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEvent;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEventType;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.TripPresenceAccessClient;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.config.TripPresenceProperties;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import com.yeodam.websocket.trip.repository.TripPresenceRepository;
import com.yeodam.websocket.trip.repository.TripPresenceRepository.ConnectionPresence;
import com.yeodam.websocket.trip.repository.TripPresenceRepository.PresenceChange;
import com.yeodam.websocket.trip.service.TripPresenceSessionRegistry.Connection;
import com.yeodam.websocket.trip.service.response.TripPresenceSnapshotResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
public class TripPresenceService {
    private static final String CLOSED_ATTRIBUTE = TripPresenceService.class.getName() + ".closed";
    private final TripPresenceSessionRegistry registry;
    private final TripPresenceAccessClient accessClient;
    private final TripPresenceRepository repository;
    private final WebSocketSessionManager sessions;
    private final TripPresenceProperties properties;
    private final StringRedisTemplate redis;
    private final WebSocketEventProperties eventProperties;
    private final ObjectMapper mapper;
    private final SimpMessagingTemplate messaging;
    private final MessageChannel outbound;
    private final String instanceId = UUID.randomUUID().toString();
    private final Object[] epochLocks = new Object[64];
    private final Map<Integer, Cursor<String>> activeTrips = new java.util.HashMap<>();
    private final Executor accessExecutor;
    private final Executor cleanupExecutor;
    private final Set<AccessRefresh> refreshing = ConcurrentHashMap.newKeySet();
    private Instant nextSnapshot = Instant.EPOCH;

    public TripPresenceService(
            TripPresenceSessionRegistry registry, TripPresenceAccessClient accessClient,
            TripPresenceRepository repository, WebSocketSessionManager sessions,
            TripPresenceProperties properties, StringRedisTemplate redis,
            WebSocketEventProperties eventProperties, ObjectMapper mapper,
            @Lazy SimpMessagingTemplate messaging,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel outbound,
            @Qualifier("tripPresenceAccessExecutor") Executor accessExecutor,
            @Qualifier("tripPresenceCleanupExecutor") Executor cleanupExecutor
    ) {
        this.registry = registry;
        this.accessClient = accessClient;
        this.repository = repository;
        this.sessions = sessions;
        this.properties = properties;
        this.redis = redis;
        this.eventProperties = eventProperties;
        this.mapper = mapper;
        this.messaging = messaging;
        this.outbound = outbound;
        this.accessExecutor = accessExecutor;
        this.cleanupExecutor = cleanupExecutor;
        for (int index = 0; index < epochLocks.length; index++) {
            epochLocks[index] = new Object();
        }
    }

    public boolean authorizeSubscription(Authentication authentication, Message<?> message) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String destination = headers.getDestination();
        if (destination == null || !destination.matches("/topic/trips/[0-9]+/presence")) {
            throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
        }
        Long tripId = parseTripId(destination.split("/")[3]);
        Context context = requireContext(tripId.toString(), message);
        String subscriptionId = headers.getSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
        }
        if (!context.connection().access().containsKey(tripId)
                && context.connection().access().size() >= properties.maxTripsPerConnection()) {
            throw new TripPresenceException(ErrorMessage.PRESENCE_LIMIT_EXCEEDED);
        }
        AccessData access = fetchAccess(context, tripId);
        registry.subscribe(context.connection(), subscriptionId, tripId, access);
        requireLive(context);
        return true;
    }

    public void join(String rawTripId, Message<?> message) {
        Long tripId = parseTripId(rawTripId);
        Context context = requireContext(rawTripId, message);
        requireSubscribed(context, tripId);
        AccessData access = fetchAccess(context, tripId);
        registry.updateAccess(context.connection(), tripId, access);
        PresenceChange change = repository.join(tripId, participation(context, access));
        if (!isLive(context) || context.connection().subscriptions(tripId).isEmpty()) {
            publishChange(repository.leave(tripId, connectionKey(context.connection().id())));
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
        publishChange(change);
        if (applySnapshotEpoch(change.snapshot())) {
            sendCurrentSnapshot(context, change.snapshot());
        }
    }

    public void heartbeat(String rawTripId, Message<?> message) {
        Long tripId = parseTripId(rawTripId);
        Context context = requireContext(rawTripId, message);
        AccessData access = requireSubscribed(context, tripId);
        if (access.accessValidUntil().isBefore(Instant.now().plusSeconds(10))) {
            AccessRefresh refresh = new AccessRefresh(context.connection(), tripId);
            if (refreshing.add(refresh)) {
                try {
                    renewAccess(context, tripId);
                } finally {
                    refreshing.remove(refresh);
                }
            }
        }
        requireLive(context);
        publishChange(repository.heartbeat(tripId, connectionKey(context.connection().id())));
        if (!isLive(context) || context.connection().subscriptions(tripId).isEmpty()) {
            publishChange(repository.leave(tripId, connectionKey(context.connection().id())));
        }
    }

    public void leave(String rawTripId, Message<?> message) {
        Long tripId = parseTripId(rawTripId);
        Context context = requireContext(rawTripId, message);
        if (context.connection().access().containsKey(tripId)) {
            publishChange(repository.leave(tripId, connectionKey(context.connection().id())));
        }
    }

    public void unsubscribe(String connectionId, String subscriptionId) {
        Long tripId = registry.unsubscribe(connectionId, subscriptionId);
        if (tripId != null) {
            removeParticipation(connectionId, tripId);
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        removeConnection(event.getSessionId());
    }

    public void rejectCommand(Message<?> message, Throwable failure) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String connectionId = headers.getSessionId();
        if (connectionId == null) {
            return;
        }
        logFailure("command", failure);
        removeConnection(connectionId);
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setSessionId(connectionId);
        error.setContentType(MimeTypeUtils.APPLICATION_JSON);
        try {
            boolean sent = outbound.send(MessageBuilder.createMessage(
                    mapper.writeValueAsBytes(new Envelope(errorCode(failure), null)), error.getMessageHeaders()));
            if (!sent) {
                sessions.closeConnection(connectionId);
            }
        } catch (RuntimeException exception) {
            sessions.closeConnection(connectionId);
            logFailure("error_delivery", exception);
        }
    }

    public String errorCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TripPresenceException presenceFailure) {
                return presenceFailure.errorMessage().name();
            }
            if (current instanceof org.springframework.security.access.AccessDeniedException) {
                return ErrorMessage.WS_SUBSCRIPTION_FORBIDDEN.name();
            }
            current = current.getCause();
        }
        return ErrorMessage.INTERNAL_SERVER_ERROR.name();
    }

    public void onSnapshotEvent(WebSocketEvent event) {
        TripPresenceSnapshotResponse snapshot = mapper.treeToValue(
                event.payload().path("data"), TripPresenceSnapshotResponse.class);
        validateSnapshot(snapshot, event.target().id());
        if (applySnapshotEpoch(snapshot)) {
            broadcastLocal(snapshot);
        }
    }

    public boolean canDeliver(Message<?> message) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String destination = headers.getDestination();
        if (headers.getMessageType() != SimpMessageType.MESSAGE || destination == null
                || !destination.startsWith("/topic/trips/") || !destination.endsWith("/presence")) {
            return true;
        }
        try {
            JsonNode body = message.getPayload() instanceof byte[] bytes
                    ? mapper.readTree(bytes) : mapper.valueToTree(message.getPayload());
            JsonNode data = body.path("data");
            Long tripId = parseTripId(destination.split("/")[3]);
            WebSocketSession session = sessions.findSession(headers.getSessionId());
            return session != null && session.isOpen() && principal(session).accessTokenExpiresAt().isAfter(Instant.now())
                    && tripId.equals(data.path("tripId").asLong())
                    && registry.canDeliver(headers.getSessionId(), headers.getSubscriptionId(), tripId,
                    data.path("epoch").asString(), Instant.now());
        } catch (RuntimeException exception) {
            logFailure("outbound", exception);
            return false;
        }
    }

    @Scheduled(scheduler = "tripPresenceAccessScheduler", fixedDelay = 1000, initialDelay = 1000)
    public void revalidateAccess() {
        for (Connection connection : registry.connections()) {
            for (Map.Entry<Long, AccessData> entry : connection.access().entrySet()) {
                if (!entry.getValue().accessValidUntil().isAfter(Instant.now().plusSeconds(10))) {
                    submitAccessRefresh(new AccessRefresh(connection, entry.getKey()));
                }
            }
        }
    }

    @Scheduled(scheduler = "tripPresenceTaskScheduler", fixedDelay = 5000, initialDelay = 5000)
    public void maintainParticipations() {
        try {
            expireParticipations();
            if (!Instant.now().isBefore(nextSnapshot)) {
                resynchronizeSnapshots();
                nextSnapshot = Instant.now().plusSeconds(30);
            }
        } catch (RuntimeException exception) {
            logFailure("maintenance", exception);
        }
    }

    private void submitAccessRefresh(AccessRefresh refresh) {
        if (!refreshing.add(refresh)) {
            return;
        }
        try {
            accessExecutor.execute(() -> refreshAccess(refresh));
        } catch (RejectedExecutionException exception) {
            refreshing.remove(refresh);
            logFailure("access_refresh_capacity", exception);
        }
    }

    private void refreshAccess(AccessRefresh refresh) {
        try {
            Context context = contextFor(refresh.connection());
            renewAccess(context, refresh.tripId());
        } catch (RuntimeException exception) {
            logFailure("access_revalidation", exception);
            removeConnection(refresh.connection().id());
            sessions.closeConnection(refresh.connection().id());
        } finally {
            refreshing.remove(refresh);
        }
    }

    private void renewAccess(Context context, Long tripId) {
        AccessData access = fetchAccess(context, tripId);
        publishChange(repository.refreshAccess(tripId, participation(context, access)));
        registry.updateAccess(context.connection(), tripId, access);
        if (!isLive(context) || context.connection().subscriptions(tripId).isEmpty()) {
            removeParticipation(context.connection().id(), tripId);
        }
    }

    private void expireParticipations() {
        for (int partition = 0; partition < properties.partitionCount(); partition++) {
            Cursor<String> cursor = activeTrips.computeIfAbsent(partition, repository::scanActiveTrips);
            try {
                for (int count = 0; count < 100 && cursor.hasNext(); count++) {
                    publishChange(repository.expire(Long.valueOf(cursor.next())));
                }
                if (!cursor.hasNext()) {
                    cursor.close();
                    activeTrips.remove(partition);
                }
            } catch (RuntimeException exception) {
                cursor.close();
                activeTrips.remove(partition);
                throw exception;
            }
        }
    }

    private void resynchronizeSnapshots() {
        Set<Long> trips = new HashSet<>();
        for (Connection connection : registry.connections()) {
            trips.addAll(connection.access().keySet());
        }
        for (Long tripId : trips) {
            PresenceChange change = repository.findSnapshot(tripId);
            publishChange(change);
            if (applySnapshotEpoch(change.snapshot())) {
                broadcastLocal(change.snapshot());
            }
        }
    }

    private Context requireContext(String rawTripId, Message<?> message) {
        parseTripId(rawTripId);
        if (!(message.getPayload() instanceof byte[] bytes) || bytes.length != 0) {
            throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
        String connectionId = headers.getSessionId();
        WebSocketSession session = connectionId == null ? null : sessions.findSession(connectionId);
        if (session == null || !session.isOpen() || !(headers.getUser() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof WebSocketPrincipal user)
                || !user.equals(principal(session))) {
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
        Context context = new Context(registry.connection(connectionId), session, user);
        requireLive(context);
        return context;
    }

    private Context contextFor(Connection connection) {
        WebSocketSession session = sessions.findSession(connection.id());
        if (session == null) {
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
        Context context = new Context(connection, session, principal(session));
        requireLive(context);
        return context;
    }

    private WebSocketPrincipal principal(WebSocketSession session) {
        Authentication authentication = (Authentication) session.getPrincipal();
        return (WebSocketPrincipal) authentication.getPrincipal();
    }

    private AccessData fetchAccess(Context context, Long tripId) {
        Object stored = context.session().getAttributes().get(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        if (!(stored instanceof WebSocketSessionCredentials credentials)) {
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
        AccessData access = accessClient.findPresenceAccess(tripId, context.principal().userId(), credentials);
        requireLive(context);
        return access;
    }

    private AccessData requireSubscribed(Context context, Long tripId) {
        AccessData access = context.connection().access().get(tripId);
        if (access == null || context.connection().subscriptions(tripId).isEmpty()) {
            throw new TripPresenceException(ErrorMessage.WS_SUBSCRIPTION_FORBIDDEN);
        }
        return access;
    }

    private boolean isLive(Context context) {
        return registry.isCurrent(context.connection()) && context.session().isOpen()
                && !Boolean.TRUE.equals(context.session().getAttributes().get(CLOSED_ATTRIBUTE))
                && context.principal().accessTokenExpiresAt().isAfter(Instant.now());
    }

    private void requireLive(Context context) {
        if (!isLive(context)) {
            removeConnection(context.connection().id());
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
    }

    private ConnectionPresence participation(Context context, AccessData access) {
        return new ConnectionPresence(connectionKey(context.connection().id()), access.userId(),
                access.nickname(), access.profileImageUrl(), access.permission(), access.checkedAt(),
                access.accessValidUntil(), context.principal().accessTokenExpiresAt());
    }

    private boolean applySnapshotEpoch(TripPresenceSnapshotResponse snapshot) {
        synchronized (epochLocks[Math.floorMod(snapshot.tripId(), epochLocks.length)]) {
            String epoch = repository.findEpoch(snapshot.tripId());
            if (epoch == null || !epoch.equals(snapshot.epoch())) {
                return false;
            }
            for (Connection connection : registry.acceptEpoch(snapshot.tripId(), epoch)) {
                removeConnection(connection.id());
                sessions.closeConnection(connection.id());
            }
            return true;
        }
    }

    private void publishChange(PresenceChange change) {
        if (!change.changed()) {
            return;
        }
        WebSocketEvent event = new WebSocketEvent(UUID.randomUUID(), WebSocketEventType.PRESENCE_SNAPSHOT,
                1, Instant.now(), new WebSocketEventTarget(WebSocketEventTargetType.TRIP, change.snapshot().tripId()),
                mapper.valueToTree(new Envelope(SuccessMessage.PRESENCE_SNAPSHOT.name(), change.snapshot())));
        byte[] encoded = mapper.writeValueAsBytes(event);
        if (encoded.length > 64 * 1024) {
            throw new TripPresenceException(ErrorMessage.PRESENCE_LIMIT_EXCEEDED);
        }
        redis.convertAndSend(eventProperties.channel(), new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void broadcastLocal(TripPresenceSnapshotResponse snapshot) {
        messaging.convertAndSend("/topic/trips/" + snapshot.tripId() + "/presence",
                new Envelope(SuccessMessage.PRESENCE_SNAPSHOT.name(), snapshot));
    }

    private void sendCurrentSnapshot(Context context, TripPresenceSnapshotResponse snapshot) {
        for (String subscription : context.connection().subscriptions(snapshot.tripId())) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(context.connection().id());
            headers.setSubscriptionId(subscription);
            headers.setDestination("/topic/trips/" + snapshot.tripId() + "/presence");
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            outbound.send(MessageBuilder.createMessage(
                    mapper.writeValueAsBytes(new Envelope(SuccessMessage.PRESENCE_SNAPSHOT.name(), snapshot)), headers.getMessageHeaders()));
        }
    }

    private void removeConnection(String connectionId) {
        WebSocketSession session = sessions.findSession(connectionId);
        if (session != null) {
            session.getAttributes().put(CLOSED_ATTRIBUTE, true);
        }
        submitCleanup(connectionId, registry.close(connectionId));
    }

    private void submitCleanup(String connectionId, Set<Long> tripIds) {
        if (tripIds.isEmpty()) {
            return;
        }
        try {
            cleanupExecutor.execute(() -> {
                for (Long tripId : tripIds) {
                    removeParticipation(connectionId, tripId);
                }
            });
        } catch (RejectedExecutionException exception) {
            // Stored deadlines remain authoritative when cleanup cannot be scheduled.
            logFailure("cleanup_capacity", exception);
        }
    }

    private void removeParticipation(String connectionId, Long tripId) {
        try {
            publishChange(repository.leave(tripId, connectionKey(connectionId)));
        } catch (RuntimeException exception) {
            logFailure("participation_cleanup", exception);
        }
    }

    private String connectionKey(String connectionId) {
        return instanceId + ":" + connectionId;
    }

    private Long parseTripId(String rawTripId) {
        try {
            long tripId = Long.parseLong(rawTripId);
            if (tripId > 0) {
                return tripId;
            }
        } catch (NumberFormatException exception) {
            throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
        }
        throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
    }

    private void validateSnapshot(TripPresenceSnapshotResponse snapshot, Long targetId) {
        if (snapshot == null || !targetId.equals(snapshot.tripId()) || snapshot.epoch() == null
                || snapshot.revision() < 0 || snapshot.members() == null || snapshot.members().size() > 100) {
            throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
        }
        Set<Long> ids = new HashSet<>();
        for (TripPresenceSnapshotResponse.Member member : snapshot.members()) {
            if (member == null || member.userId() == null || member.userId() <= 0 || !ids.add(member.userId())
                    || member.nickname() == null || member.nickname().isBlank() || member.permission() == null
                    || !Set.of("OWNER", "READ", "WRITE").contains(member.permission())) {
                throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
            }
        }
    }

    private void logFailure(String operation, Throwable failure) {
        log.atWarn().addKeyValue("event", "trip_presence").addKeyValue("operation", operation)
                .addKeyValue("failure", errorCode(failure)).addKeyValue("cause_type", failure.getClass().getName())
                .log("Trip presence operation failed.");
    }

    @PreDestroy
    public void closeCursor() {
        for (Cursor<String> cursor : activeTrips.values()) {
            cursor.close();
        }
        activeTrips.clear();
    }

    private record AccessRefresh(
            Connection connection,
            Long tripId
    ) {
    }

    private record Context(
            Connection connection,
            WebSocketSession session,
            WebSocketPrincipal principal
    ) {
    }

    private record Envelope(
            String message,
            Object data
    ) {
    }
}
