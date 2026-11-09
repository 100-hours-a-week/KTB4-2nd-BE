package com.yeodam.yeodambe.common.ratelimit;

public enum RequestRateLimitGroup {
    TRIP_READ("trip-read"),
    TRIP_CREATE("trip-create");

    private final String key;

    RequestRateLimitGroup(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
