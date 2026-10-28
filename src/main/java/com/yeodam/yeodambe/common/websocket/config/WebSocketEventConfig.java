package com.yeodam.yeodambe.common.websocket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSocketEventProperties.class)
public class WebSocketEventConfig {
}