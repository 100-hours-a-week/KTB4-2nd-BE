package com.yeodam.websocket.trip.repository;

import com.yeodam.websocket.trip.config.TripPresenceProperties;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripPresenceRepositoryTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TripPresenceRepository repository = new TripPresenceRepository(
            redis, JsonMapper.builder().build(), new TripPresenceProperties("test", 16, 100, 10));

    @ParameterizedTest
    @CsvSource({
            "PRESENCE_NOT_JOINED,여행 참여 정보가 없습니다.",
            "PRESENCE_LIMIT_EXCEEDED,여행 참여 한도를 초과했습니다.",
            "UNAUTHORIZED,인증이 필요합니다."
    })
    void 허용된_Redis_오류는_코드를_유지하고_한국어로_설명한다(String code, String message) {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), any(Object[].class)))
                .thenReturn("{\"error\":\"" + code + "\"}");

        assertThatThrownBy(() -> repository.findSnapshot(10L))
                .isInstanceOfSatisfying(TripPresenceException.class,
                        exception -> assertThat(exception.errorMessage().name()).isEqualTo(code))
                .hasMessage(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":\"UNKNOWN\"}", "{\"error\":\"FORBIDDEN\"}", "{\"error\":null}",
            "{\"error\":42}", "{\"error\":{}}", "{\"error\":[]}"
    })
    void 허용되지_않은_Redis_오류는_내부_오류로_처리한다(String response) {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), any(Object[].class)))
                .thenReturn(response);

        assertThatThrownBy(() -> repository.findSnapshot(10L))
                .isInstanceOf(TripPresenceException.class)
                .hasMessage("서버 내부 오류가 발생했습니다.");
    }
}
