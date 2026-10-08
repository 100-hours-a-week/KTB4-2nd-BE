package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    private final TokenHasher hasher =
            new TokenHasher();

    @Test
    void 같은_토큰은_같은_해시를_가지며_원본을_노출하지_않는다() {
        String firstHash = hasher.hash("refresh-token-1");

        assertThat(firstHash)
                .hasSize(64)
                .matches("[0-9a-f]+")
                .isEqualTo(hasher.hash("refresh-token-1"))
                .isNotEqualTo("refresh-token-1")
                .isNotEqualTo(hasher.hash("refresh-token-2"));
    }
}
