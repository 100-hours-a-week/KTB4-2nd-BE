package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Topics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentConversionPropertiesTest {
    @ParameterizedTest
    @CsvSource({
            "same,same,additional-light,additional-heic",
            "same,initial-heic,same,additional-heic",
            "same,initial-heic,additional-light,same",
            "initial-light,same,same,additional-heic",
            "initial-light,same,additional-light,same",
            "initial-light,initial-heic,same,same"
    })
    void 다른_종류나_레인이_공유하는_토픽_이름을_거부한다(
            String initialLight,
            String initialHeic,
            String additionalLight,
            String additionalHeic
    ) {
        assertThatThrownBy(() -> new Topics(initialLight, initialHeic, additionalLight, additionalHeic))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 서로_다른_설정_토픽_이름을_보존한다() {
        Topics configured = new Topics("initial-light", "initial-heic", "additional-light", "additional-heic");
        assertThat(configured.initialLight()).isEqualTo("initial-light");
        assertThat(configured.initialHeic()).isEqualTo("initial-heic");
        assertThat(configured.additionalLight()).isEqualTo("additional-light");
        assertThat(configured.additionalHeic()).isEqualTo("additional-heic");
    }

    @Test
    void missingExecutionFlagsDefaultToEnabledForDirectFixtures() {
        AttachmentConversionProperties properties = new AttachmentConversionProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.consumerEnabled()).isTrue();
        assertThat(properties.receiptListenerEnabled()).isTrue();
        assertThat(properties.recoveryEnabled()).isTrue();
        assertThat(properties.kafkaEnabled()).isFalse();
        assertThat(properties.recoveryExecutionEnabled()).isFalse();
    }
}
