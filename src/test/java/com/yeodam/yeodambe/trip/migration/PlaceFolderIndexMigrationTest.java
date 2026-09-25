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
class PlaceFolderIndexMigrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 장소_폴더_커서_인덱스의_컬럼_순서를_생성한다() {
        String columns = jdbcTemplate.queryForObject(
                """
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'trip_detail_places'
                  AND index_name = 'IDX_TRIP_DETAIL_PLACES_FOLDER_LIST'
                """,
                String.class
        );

        assertThat(columns).isEqualTo("trip_id,deleted_at,place_name,trip_place_id");
    }
}
