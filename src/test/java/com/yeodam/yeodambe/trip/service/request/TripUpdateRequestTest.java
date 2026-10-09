package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripUpdateRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 변경할_값이_없는_요청을_거부한다() {
        assertThat(validator.validate(new TripUpdateRequest(null, null, null, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("anyFieldPresent");
    }

    @Test
    void 각_필드의_독립적인_변경을_허용한다() {
        var date = LocalDate.of(2026, 10, 1);
        for (var request : List.of(
                new TripUpdateRequest("여행", null, null, null),
                new TripUpdateRequest(null, date, null, null),
                new TripUpdateRequest(null, null, date, null),
                new TripUpdateRequest(null, null, null, List.of("50110")))) {
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Test
    void 입력한_이름을_코드_포인트_기준으로_검증한다() {
        for (String invalid : List.of("", "   ", "12345678901")) {
            assertThat(validator.validate(new TripUpdateRequest(invalid, null, null, null)))
                    .isNotEmpty();
        }
        assertThat(validator.validate(new TripUpdateRequest("😀".repeat(10), null, null, null)))
                .isEmpty();
        assertThat(validator.validate(new TripUpdateRequest("😀".repeat(11), null, null, null)))
                .isNotEmpty();
    }

    @Test
    void 입력한_지역_개수와_코드_형식을_검증한다() {
        List<List<String>> invalidLists = List.of(
                List.<String>of(),
                List.of("5011"),
                List.of("5011A"),
                Arrays.asList("50110", null),
                List.of("50110", "50130", "11110", "11140", "11170", "11200",
                        "11215", "11230", "11260", "11290", "11305"));
        for (var codes : invalidLists) {
            assertThat(validator.validate(new TripUpdateRequest(null, null, null, codes)))
                    .isNotEmpty();
        }
        assertThat(validator.validate(new TripUpdateRequest(null, null, null,
                List.of("50110", "50130", "11110", "11140", "11170", "11200",
                        "11215", "11230", "11260", "11290"))))
                .isEmpty();
    }
}
