package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.user.entity.User;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripShareSchemaTest {
    @Test
    void 엔티티만으로_공유_컬럼과_모든_제약을_생성한다() throws Exception {
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
                .addAnnotatedClass(TripShareUser.class).buildMetadata().buildSessionFactory()) {
            verifyShareSchema(jdbc(details));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private JdbcTemplate jdbc(JdbcConnectionDetails details) {
        return new JdbcTemplate(new DriverManagerDataSource(
                details.getJdbcUrl(), details.getUsername(), details.getPassword()));
    }

    private void verifyShareSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_share_users'
                """, Long.class)).isEqualTo(9L);
        List<Map<String, Object>> ids = jdbc.queryForList("""
                SELECT COLUMN_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_share_users'
                  AND COLUMN_NAME IN ('trip_share_id', 'trip_id', 'user_id', 'grantee_user_id')
                """);
        assertThat(ids).hasSize(4).allSatisfy(column -> {
            assertThat(column.get("COLUMN_TYPE").toString()).isEqualToIgnoringCase("bigint unsigned");
            assertThat(column.get("IS_NULLABLE")).isEqualTo("NO");
        });
        assertThat(jdbc.queryForList("""
                SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_share_users'
                """, String.class)).contains("PRIMARY", "uk_trip_share_trip_grantee", "ck_trip_share_permission",
                "fk_trip_share_trip", "fk_trip_share_issuer", "fk_trip_share_grantee");
        assertThat(jdbc.queryForObject("""
                SELECT EXTRA FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_NAME = 'trip_share_id'
                """, String.class)).contains("auto_increment");
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_NAME = 'permission'
                """, String.class)).isEqualTo("READ");
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_NAME = 'permission'
                """, String.class)).isEqualTo("varchar(10)");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_TYPE = 'datetime(6)'
                  AND ((COLUMN_NAME IN ('expires_at', 'created_at', 'updated_at') AND IS_NULLABLE = 'NO')
                    OR (COLUMN_NAME = 'deleted_at' AND IS_NULLABLE = 'YES'))
                """, Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_NAME = 'created_at'
                """, String.class)).isEqualToIgnoringCase("CURRENT_TIMESTAMP(6)");
        assertThat(jdbc.queryForObject("""
                SELECT EXTRA FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND COLUMN_NAME = 'updated_at'
                """, String.class).toLowerCase()).contains("on update current_timestamp(6)");
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_share_users'
                  AND INDEX_NAME = 'uk_trip_share_trip_grantee'
                """, String.class)).isEqualTo("trip_id,grantee_user_id");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(COLUMN_NAME, ':', REFERENCED_TABLE_NAME, '.', REFERENCED_COLUMN_NAME)
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'trip_share_users' AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class)).containsExactlyInAnyOrder(
                "trip_id:trips.trip_id", "user_id:users.user_id", "grantee_user_id:users.user_id");
    }
}
