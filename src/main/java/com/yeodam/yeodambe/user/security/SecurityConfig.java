// 인증 없이 접근 가능한 공통 경로와 보호할 API 범위를 설정합니다.
package com.yeodam.yeodambe.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/csrf").permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/auth/kakao/authorize",
                        "/auth/kakao/callback"
                ).permitAll()
                .anyRequest().authenticated()
        );

        return http.build();
    }
}
