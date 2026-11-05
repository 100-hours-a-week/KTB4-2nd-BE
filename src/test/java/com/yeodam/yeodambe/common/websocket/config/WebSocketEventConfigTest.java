package com.yeodam.yeodambe.common.websocket.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketEventConfigTest {

    @Test
    void 공통_YAML_문서에서_로컬_채널을_바인딩한다() throws IOException {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebSocketEventProperties.class).channel())
                    .isEqualTo("yeodam:local:websocket:events:v1");
        });
    }

    @Test
    void 배포_채널_설정으로_기본값을_덮어쓸_수_있다() throws IOException {
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
