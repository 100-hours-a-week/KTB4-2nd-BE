package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class TokenHasher {

    public String hash(String token) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    UserInternalErrorMessage.SHA_256_UNAVAILABLE.message(),
                    e
            );
        }
    }
}