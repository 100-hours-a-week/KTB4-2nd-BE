package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CsrfTokenService {

    private final CsrfTokenGenerator csrfTokenGenerator;
    private final CsrfTokenStore csrfTokenStore;

    public String issue(String browserContext) {
        return csrfTokenStore.findOrCreate(browserContext, csrfTokenGenerator::generate);
    }
}
