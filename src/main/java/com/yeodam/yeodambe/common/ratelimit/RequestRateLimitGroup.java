package com.yeodam.yeodambe.common.ratelimit;

public enum RequestRateLimitGroup {
    TRIP_READ("trip-read"),
    TRIP_CREATE("trip-create"),
    ATTACHMENT_BULK_DOWNLOAD("attachment-bulk-download"),
    STORY_TONE_PREVIEW("story-tone-preview");

    private final String key;

    RequestRateLimitGroup(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
