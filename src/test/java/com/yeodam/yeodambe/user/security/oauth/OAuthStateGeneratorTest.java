package com.yeodam.yeodambe.user.security.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthStateGeneratorTest {

    private final OAuthStateGenerator generator =
            new OAuthStateGenerator();

    @Test
    void URL에_안전한_무작위_OAuth_상태를_생성한다() {
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