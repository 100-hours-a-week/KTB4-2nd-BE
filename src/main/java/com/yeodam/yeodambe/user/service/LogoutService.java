package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final LoginSessionStore loginSessionStore;

    public void logout(String sid) {
        loginSessionStore.deleteBySid(sid);
    }
}