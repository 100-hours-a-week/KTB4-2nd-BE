package com.yeodam.yeodambe.user.security.websocket;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WebSocketServiceAuthenticationFilterTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 서버_서비스키가_설정되지_않으면_통과시키지_않는다(String configuredKey) throws Exception {
        var filter = new WebSocketServiceAuthenticationFilter(
                new WebSocketAuthenticationProperties(configuredKey), new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Service-Token", "test-service-token");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(new ObjectMapper().readTree(response.getContentAsString())
                .path("message").asString()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(chain);
    }
}
