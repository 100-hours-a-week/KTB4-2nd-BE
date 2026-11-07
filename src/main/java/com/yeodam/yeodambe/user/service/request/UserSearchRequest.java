package com.yeodam.yeodambe.user.service.request;

import com.yeodam.yeodambe.common.validation.TextValidation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserSearchRequest(
        @NotBlank @Email @Size(max = 255) String email
) {
    public UserSearchRequest {
        email = TextValidation.stripInvisible(email);
    }

    @AssertTrue
    public boolean isEmailFreeOfInvisibleCharacters() {
        return !TextValidation.containsInvisible(email);
    }
}
