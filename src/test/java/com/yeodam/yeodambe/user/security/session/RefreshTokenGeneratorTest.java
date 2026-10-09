package com.yeodam.yeodambe.user.security.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator generator =
            new RefreshTokenGenerator();

    @Test
    void 서로_다르고_URL에_안전한_토큰을_생성한다() {
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
    }
}
