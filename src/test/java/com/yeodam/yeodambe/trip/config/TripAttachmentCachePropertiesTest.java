package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.TripAttachmentPageStore;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripAttachmentCachePropertiesTest {

    @Test
    void 공통_YAML의_기본값으로_캐시_키와_저장_스키마를_구성한다() throws IOException {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            TripAttachmentCacheProperties properties = context.getBean(TripAttachmentCacheProperties.class);
            assertThat(properties.getNamespace()).isEqualTo("photo-list");
            assertStoredSchema(properties, "photo-list:page:schema:1:", 1);
        });
    }

    @Test
    void V3_배포_환경변수로_사진_목록_캐시를_활성화한다() throws IOException {
        context().withPropertyValues("PHOTO_LIST_CACHE_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TripAttachmentCacheProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void 배포_설정으로_주입한_스키마를_키와_payload에_함께_사용한다() throws IOException {
        context().withPropertyValues(
                "PHOTO_LIST_CACHE_NAMESPACE=photo-list-staging",
                "PHOTO_LIST_CACHE_ACCESS_SCHEMA_VERSION=2",
                "PHOTO_LIST_CACHE_RESPONSE_SCHEMA_VERSION=3",
                "PHOTO_LIST_CACHE_PAGE_SCHEMA_VERSION=7"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertStoredSchema(context.getBean(TripAttachmentCacheProperties.class),
                    "photo-list-staging:page:schema:7:", 7);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "access-schema-version, 0", "access-schema-version, -1",
            "response-schema-version, 0", "response-schema-version, -1",
            "page-schema-version, 0", "page-schema-version, -1"
    })
    void 스키마_번호가_양수가_아니면_기동을_거부한다(String property, int value) throws IOException {
        context().withPropertyValues("app.photo-list-cache." + property + "=" + value).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage(TripInternalErrorMessage.PHOTO_LIST_CACHE_SETTINGS_INVALID.message()));
    }

    @Test
    void 스키마_설정이_누락되면_기동을_거부한다() {
        new ApplicationContextRunner().withUserConfiguration(CacheConfiguration.class).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    private void assertStoredSchema(TripAttachmentCacheProperties properties, String prefix, int schema) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock();
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper json = new ObjectMapper();
        TripAttachmentPageStore store = new TripAttachmentPageStore(redis, json, properties);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        store.save(new AttachmentPageKey(2L, 3L, null), new AttachmentPageData(List.of(), false, null, 4L));

        verify(values).set(key.capture(), payload.capture(), eq(Duration.ofSeconds(5)));
        assertThat(key.getValue()).startsWith(prefix);
        assertThat(json.readTree(payload.getValue()).path("schemaVersion").asInt()).isEqualTo(schema);
    }

    private ApplicationContextRunner context() throws IOException {
        PropertySource<?> commonYaml = new YamlPropertySourceLoader()
                .load("photo-list-cache-test", new ClassPathResource("application.yaml")).getFirst();
        return new ApplicationContextRunner().withUserConfiguration(CacheConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(commonYaml));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TripAttachmentCacheProperties.class)
    static class CacheConfiguration {
    }
}
