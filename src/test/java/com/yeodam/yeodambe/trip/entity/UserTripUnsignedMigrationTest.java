package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.user.entity.User;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTripUnsignedMigrationTest {
    @Test
    void 엔티티만으로_부모_PK와_참조_컬럼을_UNSIGNED로_생성한다() throws Exception {
        JdbcConnectionDetails details = SharedTestMysql.createDatabase();
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", details.getJdbcUrl())
                .applySetting("hibernate.connection.username", details.getUsername())
                .applySetting("hibernate.connection.password", details.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "create-only")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                .build();
        try (SessionFactory factory = new MetadataSources(registry)
                .addAnnotatedClass(User.class).addAnnotatedClass(Trip.class)
                .buildMetadata().buildSessionFactory()) {
            assertThat(jdbc(details).queryForList("""
                    SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME IN ('users', 'trips') AND COLUMN_NAME IN ('user_id', 'trip_id')
                    """, String.class)).hasSize(3).allMatch(type -> type.equalsIgnoreCase("bigint unsigned"));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }



    @Test
    void V25_기존데이터를_유지하며_모든_참조_ID를_UNSIGNED로_변경한다() throws Exception {
        JdbcConnectionDetails details = SharedTestMysql.createDatabase();
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target("25").load().migrate();
        JdbcTemplate jdbc = jdbc(details);
        jdbc.update("INSERT INTO users(user_id, email, nickname) VALUES(42, 'unsigned@test.com', '회원')");
        jdbc.update("""
                INSERT INTO trips(trip_id, user_id, trip_name, start_date, end_date)
                VALUES(73, 42, '여행', '2026-10-28', '2026-10-28')
                """);
        jdbc.update("""
                INSERT INTO trip_regions(trip_id, region_code, region_name, latitude, longitude)
                VALUES(73, '11000', '서울', 37.5, 127.0)
                """);
        jdbc.update("""
                INSERT INTO story_generation_jobs(execution_id, trip_id, user_id, mood, status)
                VALUES('unsigned-upgrade-job', 73, 42, 'PLAIN', 'QUEUED')
                """);
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target("26").load().migrate();
        List<String> types = jdbc.queryForList("""
                SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN ('user_id', 'trip_id', 'grantee_user_id', 'trip_share_id')
                """, String.class);
        assertThat(types).isNotEmpty().allMatch(type -> type.equalsIgnoreCase("bigint unsigned"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM trips WHERE trip_id = 73", Long.class)).isEqualTo(42L);
        assertThat(jdbc.queryForObject("SELECT region_name FROM trip_regions WHERE trip_id = 73", String.class)).isEqualTo("서울");
        assertThat(jdbc.queryForObject("SELECT active_trip_id FROM story_generation_jobs", Long.class)).isEqualTo(73L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IN ('users', 'trips')
                """, Long.class)).isEqualTo(20L);
    }

    @Test
    void 음수_ID가_있으면_FK를_해제하기_전에_중단한다() throws Exception {
        JdbcConnectionDetails details = SharedTestMysql.createDatabase();
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target("25").load().migrate();
        JdbcTemplate jdbc = jdbc(details);
        jdbc.update("INSERT INTO users(user_id, email, nickname) VALUES(-1, 'negative@test.com', '회원')");
        Long before = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                """, Long.class);
        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target("26").load().migrate())
                .hasStackTraceContaining("ck_unsigned_id_preflight");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                """, Long.class)).isEqualTo(before);
    }

    private JdbcTemplate jdbc(JdbcConnectionDetails details) {
        return new JdbcTemplate(new DriverManagerDataSource(
                details.getJdbcUrl(), details.getUsername(), details.getPassword()));
    }


}
