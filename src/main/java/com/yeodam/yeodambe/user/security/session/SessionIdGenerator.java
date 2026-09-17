package com.yeodam.yeodambe.user.security.session;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionIdGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}