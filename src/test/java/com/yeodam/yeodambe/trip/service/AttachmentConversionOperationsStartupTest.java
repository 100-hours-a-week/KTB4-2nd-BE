package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.profiles.default=none",
        "spring.config.import=",
        "app.attachment-conversion.mode=HOLD",
        "app.attachment-conversion.operations.enabled=true",
        "app.attachment-conversion.operations.action=CHECK",
        "app.attachment-conversion.operations.kind=INITIAL",
        "app.attachment-conversion.operations.after-batch-id=0",
        "app.attachment-conversion.operations.limit=10",
        "app.trip.result-listener-enabled=true",
        "app.story.result-listener-enabled=true",
        "app.outbox.receipt-listener-enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "app.trip.worker-initial-delay-ms=0",
        "app.trip.status-polling-enabled=true",
        "app.story.status-polling-enabled=true",
        "attachment.s3.bucket=test-bucket",
        "ai.server.base-url=http://localhost:9999",
        "ai.server.api-key=test",
        "ai.server.instance-id=i-test",
        "oauth.frontend-callback-uri=https://app.yeodam.test/auth/callback",
        "oauth.kakao.client-id=test-client-id",
        "oauth.kakao.redirect-uri=https://api.yeodam.test/auth/kakao/callback",
        "oauth.kakao.client-secret=test-client-secret",
        "oauth.browser-context-cookie.secure=false",
        "auth.jwt.secret=test-jwt-secret-key-must-be-at-least-32-bytes",
        "auth.jwt.issuer=https://api.yeodam.test",
        "app.cors.allowed-origin=https://app.yeodam.test"
})
@Import(TestcontainersConfiguration.class)
class AttachmentConversionOperationsStartupTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void 운영_시작은_스케줄러와_리스너와_백필을_실행하지_않는다() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("test");
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(listeners.getListenerContainers()).isEmpty();
        assertThat(context.getBeansOfType(AttachmentStorageSizeBackfillRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(PlaceSearchCatalogScheduler.class)).isEmpty();
        assertThat(context.containsBean("tripResultKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("tripUpdateResultKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("outboxReceiptKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("storyResultKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("attachmentConversionKafkaListener")).isFalse();
        assertThat(context.containsBean("attachmentConversionReceiptListener")).isFalse();
    }
}
