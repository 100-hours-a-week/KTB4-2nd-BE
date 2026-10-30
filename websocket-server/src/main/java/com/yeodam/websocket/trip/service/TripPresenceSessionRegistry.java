package com.yeodam.websocket.trip.service;

import com.yeodam.websocket.common.response.ErrorMessage;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TripPresenceSessionRegistry {
    private static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 100;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, String> epochs = new ConcurrentHashMap<>();

    public Connection connection(String connectionId) {
        return connections.computeIfAbsent(connectionId, Connection::new);
    }

    public boolean isCurrent(Connection connection) {
        return connections.get(connection.id()) == connection && !connection.closed();
    }

    public void subscribe(Connection connection, String subscriptionId, Long tripId, AccessData access) {
        synchronized (connection) {
            requireCurrent(connection);
            Long existingTrip = connection.subscriptions.get(subscriptionId);
            if (existingTrip != null && !existingTrip.equals(tripId)) {
                throw new TripPresenceException(ErrorMessage.INVALID_REQUEST);
            }
            if (existingTrip == null && connection.subscriptions.size() >= MAX_SUBSCRIPTIONS_PER_CONNECTION) {
                throw new TripPresenceException(ErrorMessage.PRESENCE_LIMIT_EXCEEDED);
            }
            connection.subscriptions.put(subscriptionId, tripId);
            connection.access.put(tripId, access);
        }
    }

    public void updateAccess(Connection connection, Long tripId, AccessData access) {
        synchronized (connection) {
            requireCurrent(connection);
            if (!connection.subscriptions.containsValue(tripId)) {
                throw new TripPresenceException(ErrorMessage.WS_SUBSCRIPTION_FORBIDDEN);
            }
            AccessData previous = connection.access.get(tripId);
            if (previous == null || !access.checkedAt().isBefore(previous.checkedAt())) {
                connection.access.put(tripId, access);
            }
        }
    }

    public Long unsubscribe(String connectionId, String subscriptionId) {
        Connection connection = connections.get(connectionId);
        if (connection == null) {
            return null;
        }
        synchronized (connection) {
            Long tripId = connection.subscriptions.remove(subscriptionId);
            if (tripId != null && !connection.subscriptions.containsValue(tripId)) {
                connection.access.remove(tripId);
                return tripId;
            }
            return null;
        }
    }

    public Set<Long> close(String connectionId) {
        Connection connection = connections.remove(connectionId);
        if (connection == null) {
            return Set.of();
        }
        synchronized (connection) {
            connection.closed = true;
            Set<Long> tripIds = Set.copyOf(connection.access.keySet());
            connection.access.clear();
            connection.subscriptions.clear();
            return tripIds;
        }
    }

    public List<Connection> connections() {
        return List.copyOf(connections.values());
    }

    public List<Connection> acceptEpoch(Long tripId, String epoch) {
        String previous = epochs.put(tripId, epoch);
        if (previous == null || previous.equals(epoch)) {
            return List.of();
        }
        return connections.values().stream()
                .filter(connection -> connection.access().containsKey(tripId)).toList();
    }

    public boolean canDeliver(
            String connectionId, String subscriptionId, Long tripId, String epoch, Instant now
    ) {
        Connection connection = connections.get(connectionId);
        if (connection == null) {
            return false;
        }
        synchronized (connection) {
            AccessData access = connection.access.get(tripId);
            return isCurrent(connection) && tripId.equals(connection.subscriptions.get(subscriptionId))
                    && access != null && access.accessValidUntil().isAfter(now)
                    && epoch != null && epoch.equals(epochs.get(tripId));
        }
    }

    private void requireCurrent(Connection connection) {
        if (!isCurrent(connection)) {
            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
        }
    }

    public static final class Connection {
        private final String id;
        private final Map<String, Long> subscriptions = new HashMap<>();
        private final Map<Long, AccessData> access = new HashMap<>();
        private boolean closed;

        private Connection(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public synchronized boolean closed() {
            return closed;
        }

        public synchronized Map<Long, AccessData> access() {
            return Map.copyOf(access);
        }

        public synchronized List<String> subscriptions(Long tripId) {
            return subscriptions.entrySet().stream().filter(entry -> tripId.equals(entry.getValue()))
                    .map(Map.Entry::getKey).toList();
        }
    }
}
