package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceSearchRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 페이지를_생략하면_첫_페이지를_사용한다() {
        PlaceSearchRequest request = new PlaceSearchRequest("제주", null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.requestPageNo()).isEqualTo(1);
    }

    @Test
    void 빈값_공백_숫자_영문_검색어를_거부한다() {
        for (String query : new String[]{null, "", " ", "제 주", "제주1", "Jeju"}) {
            assertThat(validator.validate(new PlaceSearchRequest(query, 1)))
                    .as("query=%s", query).isNotEmpty();
        }
    }

    @Test
    void 영_페이지를_거부한다() {
        assertThat(validator.validate(new PlaceSearchRequest("제주", 0))).isNotEmpty();
        assertThat(validator.validate(new PlaceSearchRequest("제주", 2))).isEmpty();
    }
}
