package com.yeodam.websocket.trip.config;

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
        Assert.hasText(namespace, "Presence namespace is required");
        Assert.isTrue(partitionCount > 0 && partitionCount <= 256, "Invalid presence partition count");
        Assert.isTrue(maxConnectionsPerTrip > 0 && maxConnectionsPerTrip <= 100,
                "Presence trip connection limit must be between 1 and 100");
        Assert.isTrue(maxTripsPerConnection > 0 && maxTripsPerConnection <= 100,
                "Invalid presence connection trip limit");
    }
}
