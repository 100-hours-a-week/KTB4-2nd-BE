package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripCreateRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 여행명_앞뒤_공백을_제거한_뒤_길이를_검증한다() {
        TripCreateRequest request = new TripCreateRequest(
                "  " + "😀".repeat(10) + "  ",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 3),
                List.of("50110")
        );

        assertThat(request.tripName()).isEqualTo("😀".repeat(10));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 위치와_관계없이_여행명의_제어문자를_거부한다() {
        for (String name : List.of("\n여행", "여행\t", "여\n행", "여행\u0000", "여행\u007F", "여행\u0085")) {
            TripCreateRequest request = new TripCreateRequest(
                    name,
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 9, 3),
                    List.of("50110")
            );

            assertThat(validator.validate(request)).as("name=%s", name).isNotEmpty();
        }
    }

    @Test
    void 필수_필드가_없으면_거부한다() {
        var violations = validator.validate(new TripCreateRequest(null, null, null, null));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("tripName", "startDate", "endDate", "regionCodes");
    }

    @Test
    void 공백이거나_열자를_초과하는_여행명은_거부한다() {
        assertThat(validator.validate(request("   ", List.of("50110")))).isNotEmpty();
        assertThat(validator.validate(request("12345678901", List.of("50110")))).isNotEmpty();
    }

    @Test
    void 이모지와_특수문자가_있는_여행명은_허용한다() {
        assertThat(validator.validate(request("제주✨!", List.of("50110")))).isEmpty();
    }

    @Test
    void 지역은_한개에서_열개까지_다섯자리_숫자여야_한다() {
        assertThat(validator.validate(request("여행", List.of()))).isNotEmpty();
        assertThat(validator.validate(request("여행", List.of("5011A")))).isNotEmpty();
        assertThat(validator.validate(request("여행", List.of("5011")))).isNotEmpty();
        assertThat(validator.validate(request("여행", List.of(
                "50110", "50130", "11110", "11140", "11170", "11200",
                "11215", "11230", "11260", "11290", "11305")))).isNotEmpty();
    }

    @Test
    void 메타데이터_없이_열개_code_point_이모지_여행명을_허용한다() {
        assertThat(validator.validate(request("😀".repeat(10), List.of("50110")))).isEmpty();
    }

    private TripCreateRequest request(
            String name,
            List<String> codes
    ) {
        return new TripCreateRequest(
                name,
                LocalDate.now(),
                LocalDate.now(),
                codes
        );
    }
}
