package com.yeodam.yeodambe.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoProfileImageUrlTest {

    @Test
    void 카카오_HTTP_주소의_경로와_쿼리를_유지하며_HTTPS로_변환한다() {
        assertThat(KakaoProfileImageUrl.toHttps("http://k.kakaocdn.net/dn/photo.jpg?size=110"))
                .isEqualTo("https://k.kakaocdn.net/dn/photo.jpg?size=110");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://k.kakaocdn.net/photo.jpg",
            "http://example.com/photo.jpg",
            "http://k.kakaocdn.net.example.com/photo.jpg",
            "http://k.kakaocdn.net@evil.example/photo.jpg"
    })
    void 변환_대상이_아닌_주소는_유지한다(String url) {
        assertThat(KakaoProfileImageUrl.toHttps(url)).isEqualTo(url);
    }
}
