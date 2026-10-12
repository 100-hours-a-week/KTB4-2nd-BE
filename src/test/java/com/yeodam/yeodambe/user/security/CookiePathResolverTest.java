package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CookiePathResolverTest {

    @Test
    void 루트_경로는_유지하고_나머지_API_경로에는_접두사를_붙인다() {
        CookiePathResolver resolver = new CookiePathResolver("/api");

        assertThat(resolver.apiPath("/")).isEqualTo("/");
        assertThat(resolver.apiPath("/auth"))
                .isEqualTo("/api/auth");
        assertThat(resolver.apiPath("/users/me/profile"))
                .isEqualTo("/api/users/me/profile");
    }
}
