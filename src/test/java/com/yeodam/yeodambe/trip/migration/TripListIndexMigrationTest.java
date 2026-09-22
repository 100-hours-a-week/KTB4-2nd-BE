package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripListIndexMigrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 여행_목록_인덱스의_컬럼_순서를_생성한다() {
        assertThat(indexColumns("IDX_TRIPS_LIST"))
                .isEqualTo("user_id,deleted_at,created_at,trip_id");
        assertThat(indexColumns("IDX_TRIPS_FAVORITE_LIST"))
                .isEqualTo("user_id,deleted_at,is_favorite,created_at,trip_id");
    }

    private String indexColumns(String indexName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'trips'
                  AND index_name = ?
                """,
                String.class,
                indexName
        );
    }
}
