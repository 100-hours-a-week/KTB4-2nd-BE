package com.yeodam.yeodambe;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "test.environment.mysql", havingValue = "isolated", matchIfMissing = true)
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(
                DockerImageName.parse("mysql:9.7.2")
        );
    }

    @Bean
    @ConditionalOnProperty(name = "test.environment.mysql", havingValue = "shared")
    JdbcConnectionDetails sharedMysqlConnectionDetails() throws SQLException {
        return SharedTestMysql.createDatabase();
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withExposedPorts(6379);
    }
}
