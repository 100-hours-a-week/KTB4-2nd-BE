package com.yeodam.websocket.common.websocket.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackendAuthenticationConfigurationTest {

    @Test
    void bindsEnvironmentValuesThroughApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BackendAuthenticationClientConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(
                        "BACKEND_INTERNAL_BASE_URL=http://127.0.0.1:18080",
                        "WEBSOCKET_INTERNAL_SERVICE_TOKEN=configuration-test-token"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    BackendAuthenticationProperties properties =
                            context.getBean(BackendAuthenticationProperties.class);

                    assertThat(properties.baseUrl())
                            .isEqualTo(URI.create("http://127.0.0.1:18080"));
                    assertThat(properties.serviceToken())
                            .isEqualTo("configuration-test-token");
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(context).hasSingleBean(RestClient.class);
                });
    }
}
