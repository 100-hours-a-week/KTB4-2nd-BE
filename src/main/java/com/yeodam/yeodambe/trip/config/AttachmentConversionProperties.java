package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

@ConfigurationProperties(prefix = "app.attachment-conversion")
public record AttachmentConversionProperties(
        AttachmentConversionRoute mode,
        Admission admission,
        Operations operations,
        Topics topics,
        Recovery recovery,
        Boolean consumerEnabled,
        Boolean receiptListenerEnabled,
        Boolean recoveryEnabled
) {
    public AttachmentConversionProperties {
        consumerEnabled = consumerEnabled == null ? Boolean.TRUE : consumerEnabled;
        receiptListenerEnabled = receiptListenerEnabled == null ? Boolean.TRUE : receiptListenerEnabled;
        recoveryEnabled = recoveryEnabled == null ? Boolean.TRUE : recoveryEnabled;
        topics = topics == null ? new Topics(null, null, null, null) : topics;
        mode = mode == null ? AttachmentConversionRoute.DB : mode;
        operations = operations == null ? new Operations(false) : operations;
        if (mode == AttachmentConversionRoute.KAFKA) {
            Assert.notNull(admission, TripInternalErrorMessage.CONVERSION_KAFKA_ADMISSION_MISSING.message());
            admission.validate();
            Assert.notNull(recovery, TripInternalErrorMessage.CONVERSION_KAFKA_RECOVERY_MISSING.message());
            recovery.validate();
        }
    }

    public boolean kafkaEnabled() {
        return mode == AttachmentConversionRoute.KAFKA && !operations.enabled();
    }

    public boolean receiptEnabled() {
        return mode != AttachmentConversionRoute.DB && !operations.enabled();
    }

    public boolean recoveryExecutionEnabled() {
        return kafkaEnabled() && recoveryEnabled;
    }

    public record Admission(
            Map<Lane, Long> maxUnfinishedPhotos,
            Map<Lane, Long> maxUnfinishedBytes,
            Duration maxUnfinishedAge,
            Duration snapshotMaxAge,
            Duration sampleInterval,
            double reopenRatio
    ) {
        public Admission {
            maxUnfinishedPhotos = maxUnfinishedPhotos == null ? Map.of() : Map.copyOf(maxUnfinishedPhotos);
            maxUnfinishedBytes = maxUnfinishedBytes == null ? Map.of() : Map.copyOf(maxUnfinishedBytes);
        }

        private void validate() {
            for (Lane lane : Lane.values()) {
                Assert.isTrue(maxUnfinishedPhotos.getOrDefault(lane, 0L) > 0,
                        TripInternalErrorMessage.CONVERSION_UNFINISHED_PHOTO_LIMIT_INVALID.message());
                Assert.isTrue(maxUnfinishedBytes.getOrDefault(lane, 0L) > 0,
                        TripInternalErrorMessage.CONVERSION_UNFINISHED_BYTE_LIMIT_INVALID.message());
            }
            requirePositive(maxUnfinishedAge);
            requirePositive(snapshotMaxAge);
            requirePositive(sampleInterval);
            Assert.isTrue(sampleInterval.compareTo(snapshotMaxAge) <= 0,
                    TripInternalErrorMessage.CONVERSION_ADMISSION_SAMPLE_INTERVAL_INVALID.message());
            Assert.isTrue(Double.isFinite(reopenRatio) && reopenRatio > 0 && reopenRatio < 1,
                    TripInternalErrorMessage.CONVERSION_ADMISSION_REOPEN_RATIO_INVALID.message());
        }

        private static void requirePositive(Duration value) {
            Assert.isTrue(value != null && value.isPositive(),
                    TripInternalErrorMessage.CONVERSION_ADMISSION_DURATION_INVALID.message());
        }
    }

    public record Recovery(
            Duration interval,
            int pageSize,
            int maxPagesPerRun,
            int maxReissuesPerRun
    ) {
        private void validate() {
            Assert.isTrue(interval != null && interval.isPositive(),
                    TripInternalErrorMessage.CONVERSION_RECOVERY_INTERVAL_INVALID.message());
            Assert.isTrue(pageSize > 0 && maxPagesPerRun > 0 && maxReissuesPerRun > 0,
                    TripInternalErrorMessage.CONVERSION_RECOVERY_BUDGET_INVALID.message());
        }
    }

    public record Topics(
            String initialLight,
            String initialHeic,
            String additionalLight,
            String additionalHeic
    ) {
        public Topics {
            initialLight = initialLight == null ? "yeodam-image-initial-light" : initialLight;
            initialHeic = initialHeic == null ? "yeodam-image-initial-heic" : initialHeic;
            additionalLight = additionalLight == null ? "yeodam-image-additional-light" : additionalLight;
            additionalHeic = additionalHeic == null ? "yeodam-image-additional-heic" : additionalHeic;
            Assert.hasText(initialLight, TripInternalErrorMessage.CONVERSION_INITIAL_LIGHT_TOPIC_MISSING.message());
            Assert.hasText(initialHeic, TripInternalErrorMessage.CONVERSION_INITIAL_HEIC_TOPIC_MISSING.message());
            Assert.hasText(additionalLight,
                    TripInternalErrorMessage.CONVERSION_ADDITIONAL_LIGHT_TOPIC_MISSING.message());
            Assert.hasText(additionalHeic, TripInternalErrorMessage.CONVERSION_ADDITIONAL_HEIC_TOPIC_MISSING.message());
            Assert.isTrue(Stream.of(initialLight, initialHeic, additionalLight, additionalHeic).distinct().count() == 4,
                    TripInternalErrorMessage.CONVERSION_TOPIC_CONFLICT.message());
        }
    }

    public record Operations(
            boolean enabled
    ) {
    }
}
