package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.service.CurrentUserService;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import com.yeodam.yeodambe.user.service.response.UserStatsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class CurrentUserControllerTest {

    @Mock
    private CurrentUserService service;

    @InjectMocks
    private CurrentUserController controller;

    @Test
    void 인증된_JWT의_사용자_ID로_현재_회원_정보를_반환한다() {
        Jwt jwt = mock(Jwt.class);
        CurrentUserResponse data = new CurrentUserResponse(
                42L,
                "user@example.com",
                "여행자",
                "KAKAO",
                true
        );
        given(jwt.getSubject()).willReturn("42");
        given(service.find(42L)).willReturn(data);

        ResponseEntity<ApiResponse<CurrentUserResponse>> response =
                controller.find(jwt);

        then(service).should().find(42L);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(
                new ApiResponse<>("USER_FOUND", data)
        );
    }
    @Test
    void 인증된_본인의_통계를_성공_응답으로_반환한다() {
        Jwt jwt = mock(Jwt.class);
        UserStatsResponse data = new UserStatsResponse(2, 10, 1, 4096, 30_000_000_000L);
        given(jwt.getSubject()).willReturn("42");
        given(service.findStats(42L)).willReturn(data);

        var response = controller.findStats(jwt);

        then(service).should().findStats(42L);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new ApiResponse<>("USER_STATS_FOUND", data));
    }
}
