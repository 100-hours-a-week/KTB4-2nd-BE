package com.yeodam.yeodambe.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.request-rate-limit")
public record RequestRateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("yeodam:v3:local") String keyPrefix,
        @DefaultValue("60s") Duration idleTtl,
        @DefaultValue Bucket tripRead,
        @DefaultValue Bucket tripCreate,
        @DefaultValue Bucket attachmentBulkDownload,
        @DefaultValue Bucket storyTonePreview
) {
    public RequestRateLimitProperties {
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{1,100}")) {
            throw new IllegalArgumentException(RequestRateLimitErrorMessage.KEY_PREFIX_INVALID.message());
        }
        if (idleTtl == null || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException(RequestRateLimitErrorMessage.TTL_INVALID.message());
        }
        if (tripRead == null) {
            tripRead = new Bucket(5, 30);
        }
        if (tripCreate == null) {
            tripCreate = new Bucket(1, 5);
        }
        if (attachmentBulkDownload == null) {
            attachmentBulkDownload = new Bucket(1, 1);
        }
        if (storyTonePreview == null) {
            storyTonePreview = new Bucket(1, 1);
        }
    }

    public record Bucket(
            @DefaultValue("1") int refillPerSecond,
            @DefaultValue("1") int capacity
    ) {
        public Bucket {
            if (refillPerSecond < 1 || capacity < 1) {
                throw new IllegalArgumentException(RequestRateLimitErrorMessage.BUCKET_INVALID.message());
            }
        }
    }
}
