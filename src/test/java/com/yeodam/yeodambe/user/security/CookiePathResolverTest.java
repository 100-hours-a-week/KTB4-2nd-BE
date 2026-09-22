package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CookiePathResolverTest {

    @Test
    void keepsRootPathAndPrefixesNonRootApiPaths() {
        CookiePathResolver resolver = new CookiePathResolver("/api");

        assertThat(resolver.apiPath("/")).isEqualTo("/");
        assertThat(resolver.apiPath("/auth"))
                .isEqualTo("/api/auth");
        assertThat(resolver.apiPath("/users/me/profile"))
                .isEqualTo("/api/users/me/profile");
    }
}
