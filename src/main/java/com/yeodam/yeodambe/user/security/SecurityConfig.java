package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.RedisCsrfTokenRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.Customizer;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;


@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfAccessDeniedHandler csrfAccessDeniedHandler,
            RedisCsrfTokenRepository redisCsrfTokenRepository,
            CookieAccessTokenResolver cookieAccessTokenResolver,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/token/exchange").permitAll()
                .requestMatchers(HttpMethod.POST, "/users/me/profile").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/token/refresh").permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/auth/kakao/authorize",
                        "/auth/kakao/callback"
                ).permitAll()
                .anyRequest().authenticated()
        );

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .accessDeniedHandler(csrfAccessDeniedHandler)
        );

        http.csrf(csrf -> csrf
                .csrfTokenRepository(redisCsrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
        );

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        http.oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(cookieAccessTokenResolver)
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .jwt(Customizer.withDefaults())
        );

        return http.build();
    }
}
