package com.yeodam.yeodambe.user.security.websocket;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSocketAuthenticationProperties.class)
public class WebSocketAuthenticationSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain webSocketAuthenticationSecurityFilterChain(
            HttpSecurity http,
            WebSocketAuthenticationProperties properties,
            CookieAccessTokenResolver cookieAccessTokenResolver,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ObjectMapper objectMapper
    ) throws Exception {
        http.securityMatcher("/internal/websocket/**");

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                        HttpMethod.GET,
                        "/internal/websocket/authentication",
                        "/internal/websocket/trips/*/presence-access"
                ).authenticated()
                .anyRequest().denyAll()
        );

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );
        http.requestCache(cache -> cache.disable());

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(
                            objectMapper.writeValueAsString(
                                    new ApiResponse<Void>(
                                            ErrorMessage.FORBIDDEN,
                                            null
                                    )
                            )
                    );
                })
        );

        http.oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(cookieAccessTokenResolver)
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .jwt(Customizer.withDefaults())
        );

        http.addFilterBefore(
                new WebSocketServiceAuthenticationFilter(
                        properties,
                        objectMapper
                ),
                BearerTokenAuthenticationFilter.class
        );

        return http.build();
    }
}