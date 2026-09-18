package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceSearchRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "제 주", "제주1", "Jeju"})
    void 잘못된_검색어를_거부한다(String query) {
        assertThat(validator.validate(new PlaceSearchRequest(query, null))).isNotEmpty();
    }

    @Test
    void 검색어가_누락되면_거부한다() {
        assertThat(validator.validate(new PlaceSearchRequest(null, null))).isNotEmpty();
    }

    @Test
    void 페이지_번호가_1보다_작으면_거부한다() {
        assertThat(validator.validate(new PlaceSearchRequest("제주", 0))).isNotEmpty();
    }
}
