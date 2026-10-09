package com.yeodam.yeodambe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedMysqlConfigurationTest {

    @Test
    void 컨텍스트별_DB를_격리하고_한_컨텍스트가_종료돼도_공유_DB는_사용할_수_있다() throws Exception {
        try (AnnotationConfigApplicationContext first = context();
             AnnotationConfigApplicationContext second = context()) {
            assertThat(first.getBeansOfType(JdbcConnectionDetails.class)).hasSize(1);
            assertThat(second.getBeansOfType(JdbcConnectionDetails.class)).hasSize(1);

            JdbcConnectionDetails firstDatabase = first.getBean(JdbcConnectionDetails.class);
            JdbcConnectionDetails secondDatabase = second.getBean(JdbcConnectionDetails.class);
            assertThat(firstDatabase.getJdbcUrl()).isNotEqualTo(secondDatabase.getJdbcUrl());
            assertThat(firstDatabase.getJdbcUrl().split("/")[2])
                    .isEqualTo(secondDatabase.getJdbcUrl().split("/")[2]);

            try (var connection = DriverManager.getConnection(
                    firstDatabase.getJdbcUrl(), firstDatabase.getUsername(), firstDatabase.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE isolation_probe (id INT PRIMARY KEY)");
                statement.execute("INSERT INTO isolation_probe VALUES (1)");
            }

            first.close();

            try (var connection = DriverManager.getConnection(
                    secondDatabase.getJdbcUrl(), secondDatabase.getUsername(), secondDatabase.getPassword());
                 var statement = connection.createStatement();
                 var tables = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.tables "
                                 + "WHERE table_schema = DATABASE() AND table_name = 'isolation_probe'")) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isZero();
            }
        }
    }

    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test-environment",
                Map.of("test.environment.mysql", "shared")
        ));
        context.register(TestcontainersConfiguration.class);
        context.refresh();
        return context;
    }
}
