package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.CsrfTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CsrfTokenService {

    private final CsrfTokenGenerator csrfTokenGenerator;
    private final CsrfTokenStore csrfTokenStore;

    public String issue(String browserContext) {
        String token = csrfTokenGenerator.generate();

        csrfTokenStore.save(
                browserContext,
                token
        );

        return token;
    }
}