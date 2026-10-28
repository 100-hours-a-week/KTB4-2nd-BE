package com.yeodam.yeodambe.common.websocket.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketEventConfigTest {

    @Test
    void bindsTheLocalChannelFromTheCommonYamlDocument() throws IOException {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebSocketEventProperties.class).channel())
                    .isEqualTo("yeodam:local:websocket:events:v1");
        });
    }

    @Test
    void allowsTheDeploymentChannelToOverrideTheDefault() throws IOException {
        context().withPropertyValues(
                "WEBSOCKET_EVENTS_CHANNEL=yeodam:staging:websocket:events:v1"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebSocketEventProperties.class).channel())
                    .isEqualTo("yeodam:staging:websocket:events:v1");
        });
    }

    private ApplicationContextRunner context() throws IOException {
        var commonYaml = new YamlPropertySourceLoader()
                .load("websocket-events-test", new ClassPathResource("application.yaml")).getFirst();
        return new ApplicationContextRunner()
                .withUserConfiguration(WebSocketEventConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(commonYaml));
    }
}
