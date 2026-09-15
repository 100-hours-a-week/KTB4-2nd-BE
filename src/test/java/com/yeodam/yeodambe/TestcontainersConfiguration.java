// 통합 테스트에서 MySQL 9.7 컨테이너를 제공하는 테스트 설정입니다.
package com.yeodam.yeodambe;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(
                DockerImageName.parse("mysql:9.7.2")
        );
    }
}