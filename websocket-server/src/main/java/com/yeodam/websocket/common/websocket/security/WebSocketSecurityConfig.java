package com.yeodam.websocket.common.websocket.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.web.csrf.CsrfChannelInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    SecurityFilterChain webSocketSecurityFilterChain(
            HttpSecurity http,
            BackendAuthenticationClient authenticationClient
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/ws").authenticated()
                .anyRequest().denyAll()
        );

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.csrf(AbstractHttpConfigurer::disable);
        http.addFilterBefore(
                new WebSocketHandshakeAuthenticationFilter(authenticationClient),
                AnonymousAuthenticationFilter.class
        );

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                )
        );

        return http.build();
    }

    @Bean
    CsrfChannelInterceptor csrfChannelInterceptor() {
        return new CsrfChannelInterceptor();
    }

    @Bean
    AuthorizationManager<Message<?>> webSocketMessageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages
    ) {
        messages
                .simpTypeMatchers(
                        SimpMessageType.CONNECT,
                        SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT,
                        SimpMessageType.HEARTBEAT
                ).authenticated()
                .simpSubscribeDestMatchers(
                        "/user/queue/ai-tasks",
                        "/user/queue/command-results"
                ).authenticated()
                .anyMessage().denyAll();

        return messages.build();
    }
}