package com.yeodam.yeodambe;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.context.annotation.Bean;

import java.sql.SQLException;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    JdbcConnectionDetails sharedMysqlConnectionDetails() throws SQLException {
        return SharedTestMysql.createDatabase();
    }

    @Bean
    DataRedisConnectionDetails sharedRedisConnectionDetails() {
        return SharedTestRedis.createDatabase();
    }

    @Bean
    KafkaConnectionDetails sharedKafkaConnectionDetails() {
        return () -> List.of(SharedTestKafka.bootstrapServers());
    }
}
