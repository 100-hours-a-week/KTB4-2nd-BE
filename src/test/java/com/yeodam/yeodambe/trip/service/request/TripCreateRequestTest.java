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

    private TripCreateRequest request(String name, List<String> codes) {
        return new TripCreateRequest(name, LocalDate.now(), LocalDate.now(), codes);
    }
}
