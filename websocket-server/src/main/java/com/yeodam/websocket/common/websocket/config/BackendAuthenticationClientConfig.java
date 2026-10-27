package com.yeodam.websocket.common.websocket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackendAuthenticationProperties.class)
public class BackendAuthenticationClientConfig {

    @Bean(destroyMethod = "close")
    HttpClient backendAuthenticationHttpClient(
            BackendAuthenticationProperties properties
    ) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    RestClient backendAuthenticationRestClient(
            RestClient.Builder builder,
            HttpClient backendAuthenticationHttpClient,
            BackendAuthenticationProperties properties
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        backendAuthenticationHttpClient
                );
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}