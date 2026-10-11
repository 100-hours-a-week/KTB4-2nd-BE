package com.yeodam.yeodambe;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SharedMysqlConfigurationTest {

    @Test
    void 서로_다른_컨텍스트는_같은_MySQL의_독립_DB를_사용한다() {
        try (var first = context();
             var second = context()) {
            assertThat(first.getBeansOfType(JdbcConnectionDetails.class)).hasSize(1);
            assertThat(second.getBeansOfType(JdbcConnectionDetails.class)).hasSize(1);
            JdbcConnectionDetails firstDatabase = first.getBean(JdbcConnectionDetails.class);
            JdbcConnectionDetails secondDatabase = second.getBean(JdbcConnectionDetails.class);
            assertThat(endpoint(firstDatabase)).isEqualTo(endpoint(secondDatabase));
            JdbcTemplate firstJdbc = jdbc(firstDatabase);
            JdbcTemplate secondJdbc = jdbc(secondDatabase);
            assertThat(database(firstJdbc)).isNotEqualTo(database(secondJdbc));
            firstJdbc.execute("CREATE TABLE isolation_probe (id INT PRIMARY KEY)");
            firstJdbc.update("INSERT INTO isolation_probe VALUES (1)");
            assertThat(secondJdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'isolation_probe'
                    """, Long.class)).isZero();
        }
    }

    @Test
    void 컨텍스트_종료와_재생성은_다른_DB와_공유_MySQL을_유지한다() {
        try (var first = context();
             var second = context()) {
            JdbcConnectionDetails firstDatabase = first.getBean(JdbcConnectionDetails.class);
            JdbcConnectionDetails secondDatabase = second.getBean(JdbcConnectionDetails.class);
            JdbcTemplate secondJdbc = jdbc(secondDatabase);
            secondJdbc.execute("CREATE TABLE isolation_probe (id INT PRIMARY KEY)");
            secondJdbc.update("INSERT INTO isolation_probe VALUES (1)");
            first.close();
            secondJdbc.update("INSERT INTO isolation_probe VALUES (2)");
            assertThat(secondJdbc.queryForObject("SELECT COUNT(*) FROM isolation_probe", Long.class))
                    .isEqualTo(2L);
            try (var recreated = context()) {
                JdbcConnectionDetails recreatedDatabase = recreated.getBean(JdbcConnectionDetails.class);
                assertThat(endpoint(recreatedDatabase)).isEqualTo(endpoint(secondDatabase));
                assertThat(database(jdbc(recreatedDatabase)))
                        .isNotIn(database(jdbc(firstDatabase)), database(secondJdbc));
            }
        }
    }

    @Test
    void 동시_DB_할당은_서로_다른_DB를_같은_MySQL에_생성한다() throws Exception {
        JdbcConnectionDetails sharedDatabase;
        try (var initial = context()) {
            sharedDatabase = initial.getBean(JdbcConnectionDetails.class);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<JdbcConnectionDetails> allocate = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try (var context = context()) {
                return context.getBean(JdbcConnectionDetails.class);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(allocate);
            var second = executor.submit(allocate);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JdbcConnectionDetails firstDatabase = first.get(30, TimeUnit.SECONDS);
            JdbcConnectionDetails secondDatabase = second.get(30, TimeUnit.SECONDS);
            assertThat(endpoint(firstDatabase)).isEqualTo(endpoint(sharedDatabase));
            assertThat(endpoint(secondDatabase)).isEqualTo(endpoint(sharedDatabase));
            assertThat(database(jdbc(firstDatabase))).isNotEqualTo(database(jdbc(secondDatabase)));
        } finally {
            start.countDown();
        }
    }

    @Test
    void 컨텍스트별_Flyway_이력은_독립_DB에_생성된다() {
        try (var first = context();
             var second = context()) {
            JdbcConnectionDetails firstDatabase = first.getBean(JdbcConnectionDetails.class);
            JdbcConnectionDetails secondDatabase = second.getBean(JdbcConnectionDetails.class);
            migrate(firstDatabase);
            migrate(secondDatabase);
            String query = "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1";
            Long firstCount = jdbc(firstDatabase).queryForObject(query, Long.class);
            assertThat(firstCount).isPositive();
            assertThat(jdbc(secondDatabase).queryForObject(query, Long.class)).isEqualTo(firstCount);
            jdbc(firstDatabase).update("DELETE FROM flyway_schema_history");
            assertThat(jdbc(secondDatabase).queryForObject(query, Long.class)).isEqualTo(firstCount);
        }
    }

    private void migrate(JdbcConnectionDetails details) {
        Flyway.configure()
                .dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .load()
                .migrate();
    }

    private AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(TestcontainersConfiguration.class);
    }

    private JdbcTemplate jdbc(JdbcConnectionDetails details) {
        return new JdbcTemplate(new DriverManagerDataSource(
                details.getJdbcUrl(), details.getUsername(), details.getPassword()
        ));
    }

    private String database(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT DATABASE()", String.class);
    }

    private String endpoint(JdbcConnectionDetails details) {
        return details.getJdbcUrl().substring(0, details.getJdbcUrl().lastIndexOf('/'));
    }
}
