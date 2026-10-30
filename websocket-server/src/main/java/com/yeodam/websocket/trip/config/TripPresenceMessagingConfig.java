package com.yeodam.websocket.trip.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TripPresenceProperties.class)
public class TripPresenceMessagingConfig {
}
