package com.yeodam.yeodambe;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SharedMysqlFlywayIntegrationTest {

    @Autowired
    private JdbcConnectionDetails connectionDetails;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void DataSource와_Flyway는_할당된_DB를_사용하고_모든_마이그레이션을_적용한다() {
        JdbcTemplate connectionJdbc = new JdbcTemplate(new DriverManagerDataSource(
                connectionDetails.getJdbcUrl(), connectionDetails.getUsername(), connectionDetails.getPassword()
        ));
        JdbcTemplate flywayJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        String database = connectionJdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        assertThat(flywayJdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Long.class
        )).isEqualTo((long) flyway.info().applied().length);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Long.class
        )).isZero();
    }
}
