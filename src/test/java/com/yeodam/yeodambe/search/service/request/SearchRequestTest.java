package com.yeodam.yeodambe.search.service.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\u3164", "\u2800", "\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF",
            "\u00A0", "\u2007", "\u202F", " \u3164\u2800\u200B\u2060\uFEFF "
    })
    void 유니코드_공백만_있는_검색어는_거부한다(String query) {
        assertThat(validator.validate(new SearchRequest(query))).isNotEmpty();
        assertThat(validator.validate(new SearchRequest(query.repeat(2)))).isNotEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "가", "제주\t사진", "바다\n사진", "제주\u0000사진"})
    void 기존_필수값과_길이와_제어문자_검증을_유지한다(String query) {
        assertThat(validator.validate(new SearchRequest(query))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"제주 여행", "!?", "ㄱㄴ", "e\u0301", "👨‍👩‍👧‍👦", "제주\u200B"})
    void 표시할_내용이_있는_검색어는_변경하지_않는다(String query) {
        SearchRequest request = new SearchRequest("  " + query + "  ");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.query()).isEqualTo(query);
    }

    @Test
    void 검색어_길이는_공백_제거_후_코드_포인트_기준이다() {
        assertThat(validator.validate(new SearchRequest("😀"))).isNotEmpty();
        assertThat(validator.validate(new SearchRequest("😀😀"))).isEmpty();
        assertThat(validator.validate(new SearchRequest("  " + "😀".repeat(100) + "  ")))
                .isEmpty();
        assertThat(validator.validate(new SearchRequest("😀".repeat(101)))).isNotEmpty();
    }
}
