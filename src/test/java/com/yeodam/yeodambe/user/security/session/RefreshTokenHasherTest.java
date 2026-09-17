package com.yeodam.yeodambe.user.security.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHasherTest {

    private final RefreshTokenHasher hasher =
            new RefreshTokenHasher();

    @Test
    void sameTokenHasSameHashWithoutExposingRawValue() {
        String firstHash = hasher.hash("refresh-token-1");

        assertThat(firstHash)
                .hasSize(64)
                .matches("[0-9a-f]+")
                .isEqualTo(hasher.hash("refresh-token-1"))
                .isNotEqualTo("refresh-token-1")
                .isNotEqualTo(hasher.hash("refresh-token-2"));
    }
}
