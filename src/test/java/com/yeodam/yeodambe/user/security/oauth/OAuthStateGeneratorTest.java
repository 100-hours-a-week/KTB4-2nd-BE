package com.yeodam.yeodambe.user.security.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthStateGeneratorTest {

    private final OAuthStateGenerator generator =
            new OAuthStateGenerator();

    @Test
    void generatesUrlSafeRandomState() {
        String firstState = generator.generate();
        String secondState = generator.generate();

        assertThat(firstState)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");

        assertThat(secondState)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+")
                .isNotEqualTo(firstState);
    }
}