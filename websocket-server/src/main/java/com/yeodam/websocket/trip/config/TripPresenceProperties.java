package com.yeodam.websocket.trip.config;

import com.yeodam.websocket.trip.exception.TripInternalErrorMessage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

@ConfigurationProperties("websocket.presence")
public record TripPresenceProperties(
        @DefaultValue("yeodam:local:presence") String namespace,
        @DefaultValue("16") int partitionCount,
        @DefaultValue("100") int maxConnectionsPerTrip,
        @DefaultValue("10") int maxTripsPerConnection
) {
    public TripPresenceProperties {
        Assert.hasText(namespace, TripInternalErrorMessage.PRESENCE_NAMESPACE_MISSING.message());
        Assert.isTrue(partitionCount > 0 && partitionCount <= 256, TripInternalErrorMessage.PRESENCE_PARTITION_COUNT_INVALID.message());
        Assert.isTrue(maxConnectionsPerTrip > 0 && maxConnectionsPerTrip <= 100,
                TripInternalErrorMessage.PRESENCE_TRIP_CONNECTION_LIMIT_INVALID.message());
        Assert.isTrue(maxTripsPerConnection > 0 && maxTripsPerConnection <= 100,
                TripInternalErrorMessage.PRESENCE_CONNECTION_TRIP_LIMIT_INVALID.message());
    }
}
