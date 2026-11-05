package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TripAttachmentPageStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TripAttachmentCacheProperties properties;

    public Optional<AttachmentPageData> find(AttachmentPageKey query, long sequence) {
        String value = redis.opsForValue().get(key(query, sequence));
        if (value == null) {
            return Optional.empty();
        }
        CachedPage cached = json.readValue(value, CachedPage.class);
        if (cached == null || cached.schemaVersion() != properties.getPageSchemaVersion()
                || !Objects.equals(cached.key(), query) || cached.page() == null
                || cached.page().sequence() != sequence) {
            return Optional.empty();
        }
        return Optional.of(cached.page());
    }

    public void save(AttachmentPageKey query, AttachmentPageData page) {
        Duration ttl = properties.getTtl();
        if (page.items().isEmpty()) {
            if (query.cursor() != null) {
                return;
            }
            ttl = properties.getEmptyPageTtl();
        }
        redis.opsForValue().set(key(query, page.sequence()),
                json.writeValueAsString(new CachedPage(properties.getPageSchemaVersion(), query, page)), ttl);
    }

    private String key(AttachmentPageKey query, long sequence) {
        String identity = properties.getPageSchemaVersion() + "|" + query.tripId() + "|"
                + query.tripPlaceId() + "|" + query.cursor();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return properties.getNamespace() + ":page:schema:" + properties.getPageSchemaVersion()
                    + ":" + sequence + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_CACHE_KEY_UNAVAILABLE.message(), exception);
        }
    }

    private record CachedPage(
            int schemaVersion,
            AttachmentPageKey key,
            AttachmentPageData page
    ) {
    }
}
