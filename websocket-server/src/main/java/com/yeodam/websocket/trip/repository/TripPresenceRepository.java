package com.yeodam.websocket.trip.repository;

import com.yeodam.websocket.trip.config.TripPresenceProperties;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import com.yeodam.websocket.trip.service.response.TripPresenceSnapshotResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TripPresenceRepository {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TripPresenceProperties properties;
    private final DefaultRedisScript<String> script;

    public TripPresenceRepository(
            StringRedisTemplate redis, ObjectMapper mapper, TripPresenceProperties properties
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("trip/presence.lua"));
        this.script.setResultType(String.class);
    }

    public PresenceChange join(Long tripId, ConnectionPresence presence) {
        return executeMutation("join", tripId, presence.connectionKey(), presence);
    }

    public PresenceChange refreshAccess(Long tripId, ConnectionPresence presence) {
        return executeMutation("refresh", tripId, presence.connectionKey(), presence);
    }

    public PresenceChange heartbeat(Long tripId, String connectionKey) {
        return executeMutation("heartbeat", tripId, connectionKey, null);
    }

    public PresenceChange leave(Long tripId, String connectionKey) {
        return executeMutation("leave", tripId, connectionKey, null);
    }

    public PresenceChange expire(Long tripId) {
        return findSnapshot(tripId);
    }

    public PresenceChange findSnapshot(Long tripId) {
        return executeMutation("snapshot", tripId, "", null);
    }

    public String findEpoch(Long tripId) {
        Object epoch = redis.opsForHash().get(tripPrefix(tripId) + ":meta", "epoch");
        return epoch == null ? null : epoch.toString();
    }

    public Cursor<String> scanActiveTrips(int partition) {
        return redis.opsForSet().scan(partitionPrefix(partition) + ":active-trips",
                ScanOptions.scanOptions().count(100).build());
    }

    private PresenceChange executeMutation(
            String operation, Long tripId, String connectionKey, ConnectionPresence presence
    ) {
        String prefix = tripPrefix(tripId);
        List<String> keys = List.of(prefix + ":members", prefix + ":deadlines", prefix + ":meta",
                partitionPrefix(Math.floorMod(tripId, properties.partitionCount())) + ":active-trips");
        String result = redis.execute(script, keys, operation, connectionKey,
                encodePresence(presence), "45000", Integer.toString(properties.maxConnectionsPerTrip()),
                UUID.randomUUID().toString(), tripId.toString());
        if (result == null) {
            throw new TripPresenceException("INTERNAL_SERVER_ERROR");
        }
        JsonNode decoded = mapper.readTree(result);
        if (decoded.has("error")) {
            throw new TripPresenceException(decoded.path("error").asString());
        }
        return new PresenceChange(decoded.path("changed").asBoolean(),
                mapper.treeToValue(decoded.path("snapshot"), TripPresenceSnapshotResponse.class));
    }

    private String encodePresence(ConnectionPresence presence) {
        if (presence == null) {
            return "{}";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("connectionKey", presence.connectionKey());
        values.put("userId", presence.userId().toString());
        values.put("nickname", presence.nickname());
        values.put("profileImageUrl", presence.profileImageUrl());
        values.put("permission", presence.permission());
        values.put("checkedAt", presence.checkedAt().toEpochMilli());
        values.put("accessValidUntil", presence.accessValidUntil().toEpochMilli());
        values.put("tokenExpiresAt", presence.tokenExpiresAt().toEpochMilli());
        return mapper.writeValueAsString(values);
    }

    private String tripPrefix(Long tripId) {
        return partitionPrefix(Math.floorMod(tripId, properties.partitionCount())) + ":trip:" + tripId;
    }

    private String partitionPrefix(int partition) {
        return properties.namespace() + ":{presence-" + partition + "}";
    }

    public record ConnectionPresence(
            String connectionKey,
            Long userId,
            String nickname,
            String profileImageUrl,
            String permission,
            Instant checkedAt,
            Instant accessValidUntil,
            Instant tokenExpiresAt
    ) {
    }

    public record PresenceChange(
            boolean changed,
            TripPresenceSnapshotResponse snapshot
    ) {
    }
}
