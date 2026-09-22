package com.yeodam.yeodambe.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CookiePathResolver {

    private final String publicApiPrefix;

    public CookiePathResolver(
            @Value("${app.public-api-prefix}") String publicApiPrefix
    ) {
        this.publicApiPrefix = publicApiPrefix;
    }

    public String apiPath(String path) {
        if ("/".equals(path)) {
            return path;
        }

        return publicApiPrefix + path;
    }
}
