package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.RdbCsrfTokenRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.config.ObjectPostProcessor;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.Customizer;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfAccessDeniedHandler csrfAccessDeniedHandler,
            RdbCsrfTokenRepository rdbCsrfTokenRepository,
            CookieAccessTokenResolver cookieAccessTokenResolver,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint
    ) throws Exception {
        http.cors(Customizer.withDefaults());
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
                .csrfTokenRepository(rdbCsrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                    @Override
                    public <O extends CsrfFilter> O postProcess(O filter) {
                        // 쿠키의 accessToken도 Bearer 토큰이므로 Resource Server 기본 CSRF 제외를 되돌린다.
                        filter.setRequireCsrfProtectionMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER);
                        return filter;
                    }
                })
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
    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origin}") String allowedOrigin
    ) {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Content-Type",
                "X-CSRF-TOKEN"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
