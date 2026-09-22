package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @DeleteMapping("/users/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Jwt jwt
    ) {
        withdrawalService.withdraw(Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}