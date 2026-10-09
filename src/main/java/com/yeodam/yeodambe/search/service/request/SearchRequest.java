package com.yeodam.yeodambe.search.service.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotBlank
        @Size(min = 2, max = 100)
        @Pattern(regexp = "^[가-힣A-Za-z0-9 ]+$")
        String query
) {
    public SearchRequest {
        if (query != null) {
            query = query.strip();
        }
    }
}
