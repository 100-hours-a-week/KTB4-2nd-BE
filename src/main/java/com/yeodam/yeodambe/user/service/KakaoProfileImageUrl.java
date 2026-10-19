package com.yeodam.yeodambe.user.service;

public final class KakaoProfileImageUrl {

    private KakaoProfileImageUrl() {
    }

    public static String toHttps(String url) {
        if (url == null || !url.startsWith("http://k.kakaocdn.net/")) {
            return url;
        }

        return "https://" + url.substring("http://".length());
    }
}
