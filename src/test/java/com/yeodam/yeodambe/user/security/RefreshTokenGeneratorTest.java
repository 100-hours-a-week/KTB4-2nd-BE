package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator generator =
            new RefreshTokenGenerator();

    @Test
    void generatesDistinctUrlSafeTokens() {
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
    }
}
