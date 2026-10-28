package com.yeodam.websocket.common.websocket.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.yeodam.websocket.common.websocket.exception.WebSocketConfigurationFailure;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketRedisSettingsTest {

    @Test
    void resolvesTheLocalDefaults() {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WebSocketEventProperties.class);
            assertThat(context.getBean(WebSocketProperties.class).maxConnections()).isEqualTo(1000);
            Binder binder = Binder.get(context.getEnvironment());
            assertThat(binder.bind("spring.data.redis.host", String.class).get()).isEqualTo("localhost");
            assertThat(binder.bind("spring.data.redis.port", Integer.class).get()).isEqualTo(6379);
            assertThat(binder.bind("spring.data.redis.ssl.enabled", Boolean.class).get()).isFalse();
            assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
            assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
            assertThat(binder.bind("websocket.events.channel", String.class).get())
                    .isEqualTo("yeodam:local:websocket:events:v1");
        });
    }

    @Test
    void resolvesDeploymentOverrides() {
        context().withPropertyValues(
                "REDIS_HOST=redis.test.internal", "REDIS_PORT=16379",
                "REDIS_USERNAME=test-user", "REDIS_PASSWORD=test-password",
                "REDIS_SSL_ENABLED=true", "REDIS_CONNECT_TIMEOUT=3s", "REDIS_TIMEOUT=4s",
                "WEBSOCKET_EVENTS_CHANNEL=yeodam:staging:websocket:events:v1",
                "WEBSOCKET_MAX_CONNECTIONS=250"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WebSocketEventProperties.class);
            assertThat(context.getBean(WebSocketProperties.class).maxConnections()).isEqualTo(250);
            Binder binder = Binder.get(context.getEnvironment());
            assertThat(binder.bind("spring.data.redis.host", String.class).get()).isEqualTo("redis.test.internal");
            assertThat(binder.bind("spring.data.redis.port", Integer.class).get()).isEqualTo(16379);
            assertThat(binder.bind("spring.data.redis.username", String.class).get()).isEqualTo("test-user");
            assertThat(binder.bind("spring.data.redis.password", String.class).get()).isEqualTo("test-password");
            assertThat(binder.bind("spring.data.redis.ssl.enabled", Boolean.class).get()).isTrue();
            assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(3));
            assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(4));
            assertThat(binder.bind("websocket.events.channel", String.class).get())
                    .isEqualTo("yeodam:staging:websocket:events:v1");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveConnectionLimitsDuringConfigurationBinding(int limit) {
        context().withPropertyValues("WEBSOCKET_MAX_CONNECTIONS=" + limit).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage(WebSocketConfigurationFailure.INVALID_MAX_CONNECTIONS.name());
        });
    }

    @Test
    void acceptsTheMinimumPositiveConnectionLimit() {
        context().withPropertyValues("WEBSOCKET_MAX_CONNECTIONS=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebSocketProperties.class).maxConnections()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({WebSocketEventProperties.class, WebSocketProperties.class})
    static class SettingsOnlyConfig {
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(SettingsOnlyConfig.class).withInitializer(context -> {
            context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }
}
