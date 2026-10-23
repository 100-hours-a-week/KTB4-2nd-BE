package com.yeodam.yeodambe.search.service.request;

import com.yeodam.yeodambe.common.validation.TextValidation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SearchRequest(
        @NotBlank
        @Pattern(regexp = "^[^\\p{Cc}]+$")
        String query
) {
    public SearchRequest {
        if (query != null) {
            query = query.strip();
        }
    }

    @AssertTrue
    public boolean isQueryContentValid() {
        return TextValidation.hasVisibleContent(query);
    }

    @AssertTrue
    public boolean isQueryLengthValid() {
        if (query == null) {
            return true;
        }
        int length = query.codePointCount(0, query.length());
        return length >= 2 && length <= 100;
    }
}
