package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.trip.edit-lock")
public record TripEditLockProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("yeodam:local") String namespace,
        @DefaultValue("2s") Duration waitTime,
        @DefaultValue("30s") Duration watchdogTimeout
) {
    public TripEditLockProperties {
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_-]{1,100}")) {
            throw new IllegalArgumentException(TripInternalErrorMessage.EDIT_LOCK_NAMESPACE_INVALID.message());
        }
        if (waitTime == null || waitTime.isNegative() || waitTime.isZero()
                || watchdogTimeout == null || watchdogTimeout.isNegative() || watchdogTimeout.isZero()) {
            throw new IllegalArgumentException(TripInternalErrorMessage.EDIT_LOCK_DURATION_INVALID.message());
        }
    }
}
