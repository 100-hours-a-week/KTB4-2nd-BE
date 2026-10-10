package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripCreationValidationRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"takenAt\":null,\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":\"2026-10-11T10:30:00+09:00\",\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":null,\"latitude\":0,\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":90,\"longitude\":180}",
            "{\"takenAt\":null,\"latitude\":-90,\"longitude\":-180}"
    })
    void 유효한_메타데이터와_null을_허용한다(String photo) {
        var request = read(photo);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.attachmentMetadata()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":null,\"longitude\":null}", "{\"takenAt\":null,\"latitude\":null}",
            "42", "[]", "\"photo\"",
            "{\"takenAt\":42,\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":\"\",\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":\"2026-10-11T10:30:00\",\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":\"2026-02-30T10:30:00Z\",\"latitude\":null,\"longitude\":null}",
            "{\"takenAt\":null,\"latitude\":\"0\",\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":0,\"longitude\":\"0\"}",
            "{\"takenAt\":null,\"latitude\":{},\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":false,\"longitude\":0}"
    })
    void 키_누락과_잘못된_타입_시각을_거절한다(String photo) {
        assertThatThrownBy(() -> read(photo)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"takenAt\":null,\"latitude\":1,\"longitude\":null}",
            "{\"takenAt\":null,\"latitude\":null,\"longitude\":1}",
            "{\"takenAt\":null,\"latitude\":90.01,\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":-90.01,\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":0,\"longitude\":180.01}",
            "{\"takenAt\":null,\"latitude\":0,\"longitude\":-180.01}",
            "{\"takenAt\":null,\"latitude\":90.000000000000001,\"longitude\":0}",
            "{\"takenAt\":null,\"latitude\":0,\"longitude\":180.000000000000001}",
            "null"
    })
    void 좌표_쌍과_범위_요소_null을_검증한다(String photo) {
        assertThat(validator.validate(read(photo))).isNotEmpty();
    }

    @Test
    void 배열은_필수이며_한개에서_이백개까지_허용한다() {
        for (String json : new String[]{"{}", "{\"attachmentMetadata\":null}", "{\"attachmentMetadata\":[]}"}) {
            var request = objectMapper.readValue(json, TripCreationValidationRequest.class);
            assertThat(validator.validate(request)).isNotEmpty();
        }
        String photo = "{\"takenAt\":null,\"latitude\":null,\"longitude\":null}";
        var maximumRequest = read(String.join(",", Collections.nCopies(200, photo)));
        var oversizedRequest = read(String.join(",", Collections.nCopies(201, photo)));
        assertThat(maximumRequest.attachmentMetadata()).hasSize(200);
        assertThat(validator.validate(maximumRequest)).isEmpty();
        assertThat(validator.validate(oversizedRequest)).isNotEmpty();
    }

    @Test
    void 촬영시각의_오프셋과_좌표의_원본_정밀도를_보존한다() {
        var photo = read("""
                {"takenAt":"2026-10-11T10:30:00+09:00",
                 "latitude":12.123456789012345678,"longitude":0}
                """).attachmentMetadata().getFirst();

        assertThat(photo.takenAt()).isEqualTo(OffsetDateTime.parse("2026-10-11T10:30:00+09:00"));
        assertThat(photo.latitude()).isEqualByComparingTo(new BigDecimal("12.123456789012345678"));
        assertThat(photo.longitude()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private TripCreationValidationRequest read(String photo) {
        return objectMapper.readValue("{\"attachmentMetadata\":[" + photo + "]}",
                TripCreationValidationRequest.class);
    }
}
