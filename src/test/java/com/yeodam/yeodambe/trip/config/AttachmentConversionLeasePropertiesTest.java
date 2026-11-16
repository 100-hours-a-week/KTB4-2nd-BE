package com.yeodam.yeodambe.trip.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class AttachmentConversionLeasePropertiesTest {
    private ApplicationContextRunner context() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("lease", new ClassPathResource("application.yaml")).getFirst();
        return new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withInitializer(c -> c.getEnvironment().getPropertySources().addLast(yaml));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AttachmentConversionLeaseProperties.class)
    static class Config { }

    @Test void 기본_정책은_600초와_30초를_보존한다() throws Exception {
        context().run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(AttachmentConversionLeaseProperties.class)).isEqualTo(
                    new AttachmentConversionLeaseProperties(Duration.ofSeconds(600), Duration.ofSeconds(30)));
        });
    }
    @Test void 배포_환경변수로_동일한_정책_객체를_주입한다() throws Exception {
        context().withPropertyValues("ATTACHMENT_CONVERSION_LEASE_DURATION=120s",
                "ATTACHMENT_CONVERSION_LEASE_RENEW_INTERVAL=30s").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(AttachmentConversionLeaseProperties.class)).isEqualTo(
                    new AttachmentConversionLeaseProperties(Duration.ofSeconds(120), Duration.ofSeconds(30)));
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"duration=0s", "duration=-1s", "duration=59s", "duration=invalid",
            "duration=0.000000001s", "renew-interval=0s", "renew-interval=-1s",
            "renew-interval=0.5s", "renew-interval=300s", "renew-interval=0.000000001s"})
    void 여유가_없거나_잘못된_설정은_기동을_거부한다(String setting) throws Exception {
        context().withPropertyValues("app.attachment-conversion.lease." + setting).run(c -> assertThat(c).hasFailed());
    }
}
