package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileTokenGeneratorTest {

    private final ProfileTokenGenerator generator =
            new ProfileTokenGenerator();

    @Test
    void generatesUrlSafeRandomToken() {
        String firstToken = generator.generate();
        String secondToken = generator.generate();

        assertThat(firstToken)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");

        assertThat(secondToken)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+")
                .isNotEqualTo(firstToken);
    }
}
