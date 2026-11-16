package com.yeodam.yeodambe.trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.time.Duration;

/** Lease is measured with DB time; the local heartbeat scheduler uses monotonic time. */
@ConfigurationProperties(prefix = "app.attachment-conversion.lease")
public record AttachmentConversionLeaseProperties(Duration duration, Duration renewInterval) {
    public AttachmentConversionLeaseProperties {
        duration = duration == null ? Duration.ofMinutes(10) : duration;
        renewInterval = renewInterval == null ? Duration.ofSeconds(30) : renewInterval;
        Assert.isTrue(!renewInterval.isNegative() && renewInterval.compareTo(Duration.ofSeconds(1)) >= 0,
                "사진 변환 lease 갱신 주기는 1초 이상이어야 합니다.");
        Assert.isTrue(renewInterval.getNano() % 1_000_000 == 0 && duration.getNano() % 1_000_000 == 0,
                "사진 변환 lease 설정은 밀리초 단위여야 합니다.");
        Assert.isTrue(renewInterval.compareTo(Duration.ofNanos(Long.MAX_VALUE).dividedBy(3)) <= 0,
                "사진 변환 lease 갱신 주기가 너무 큽니다.");
        // Allows two missed renewal opportunities; this is a configuration floor, not a safety guarantee.
        Assert.isTrue(duration.compareTo(renewInterval.multipliedBy(3)) >= 0,
                "사진 변환 lease는 갱신 주기의 3배 이상이어야 합니다.");
    }
}
