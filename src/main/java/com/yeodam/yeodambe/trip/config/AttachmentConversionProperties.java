package com.yeodam.yeodambe.trip.config;

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
        Topics topics
) {
    public AttachmentConversionProperties {
        topics = topics == null ? new Topics(null, null, null, null) : topics;
        mode = mode == null ? AttachmentConversionRoute.DB : mode;
        operations = operations == null ? new Operations(false) : operations;
        if (mode == AttachmentConversionRoute.KAFKA) {
            Assert.notNull(admission, "KAFKA 모드에는 admission 설정이 필요합니다.");
            admission.validate();
        }
    }

    public boolean kafkaEnabled() {
        return mode == AttachmentConversionRoute.KAFKA && !operations.enabled();
    }

    public boolean receiptEnabled() {
        return mode != AttachmentConversionRoute.DB && !operations.enabled();
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
                        "각 lane의 미완료 사진 수 한도는 양수여야 합니다.");
                Assert.isTrue(maxUnfinishedBytes.getOrDefault(lane, 0L) > 0,
                        "각 lane의 미완료 바이트 한도는 양수여야 합니다.");
            }
            requirePositive(maxUnfinishedAge);
            requirePositive(snapshotMaxAge);
            requirePositive(sampleInterval);
            Assert.isTrue(sampleInterval.compareTo(snapshotMaxAge) <= 0,
                    "관측 간격은 snapshot 유효 기간 이하여야 합니다.");
            Assert.isTrue(Double.isFinite(reopenRatio) && reopenRatio > 0 && reopenRatio < 1,
                    "재허용 비율은 0 초과 1 미만이어야 합니다.");
        }

        private static void requirePositive(Duration value) {
            Assert.isTrue(value != null && value.isPositive(), "admission 시간 설정은 양수여야 합니다.");
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
            Assert.hasText(initialLight, "초기 LIGHT 토픽이 필요합니다.");
            Assert.hasText(initialHeic, "초기 HEIC 토픽이 필요합니다.");
            Assert.hasText(additionalLight, "추가 LIGHT 토픽이 필요합니다.");
            Assert.hasText(additionalHeic, "추가 HEIC 토픽이 필요합니다.");
            Assert.isTrue(Stream.of(initialLight, initialHeic, additionalLight, additionalHeic).distinct().count() == 4,
                    "네 변환 작업 토픽은 서로 달라야 합니다.");
        }
    }

    public record Operations(
            boolean enabled
    ) {
    }
}
