package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.photo-list-cache")
@Getter
@Setter
public class TripAttachmentCacheProperties implements InitializingBean {

    private boolean enabled;
    private String namespace = "photo-list";
    private int accessSchemaVersion;
    private int responseSchemaVersion;
    private int pageSchemaVersion;
    private Duration ttl = Duration.ofSeconds(60);
    private Duration emptyPageTtl = Duration.ofSeconds(5);
    private Duration readLockLease = Duration.ofSeconds(10);

    @Override
    public void afterPropertiesSet() {
        if (namespace == null || !namespace.matches("[a-zA-Z0-9:_-]{1,100}")
                || accessSchemaVersion <= 0 || responseSchemaVersion <= 0 || pageSchemaVersion <= 0
                || !positiveMillis(ttl) || !positiveMillis(emptyPageTtl) || !positiveMillis(readLockLease)) {
            throw new IllegalArgumentException(TripInternalErrorMessage.PHOTO_LIST_CACHE_SETTINGS_INVALID.message());
        }
    }

    private boolean positiveMillis(Duration value) {
        return value != null && !value.isNegative() && value.toMillis() > 0;
    }
}
