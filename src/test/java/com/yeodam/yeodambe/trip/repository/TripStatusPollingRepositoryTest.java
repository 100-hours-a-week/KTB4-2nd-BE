package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TripStatusPollingRepositoryTest.MysqlConfig.class)
class TripStatusPollingRepositoryTest {
    @Autowired private TripCreationExecutionRepository creations;
    @Autowired private AdditionalAttachmentExecutionRepository additions;
    @Autowired private JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class MysqlConfig {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectsOnlyPublishedProcessingExecutionsWithoutAResult(boolean creation) {
        String table = creation ? "trip_creation_executions" : "additional_attachment_executions";
        jdbc.update("INSERT INTO users (user_id,email,nickname) VALUES (89000,'polling@test.local','polling')");
        var fixtures = List.of(
                new Fixture("ready", "AI_QUEUED", "PROCESSING", true, false, false),
                new Fixture("uploading", "UPLOADING", "PROCESSING", false, false, false),
                new Fixture("converting", "IMAGE_PROCESSING", "PROCESSING", false, false, false),
                new Fixture("finalizing", "AI_QUEUED", "FINALIZING", true, false, false),
                new Fixture("result", "RESULT_APPLYING", "FINALIZING", true, true, false),
                new Fixture("completed", "COMPLETED", "COMPLETED", true, true, true),
                new Fixture("failed", "FAILED", "FAILED", true, false, true),
                new Fixture("canceled", "CANCELED", "CANCELED", true, false, true));
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            long tripId = 89001L + i;
            jdbc.update("""
                    INSERT INTO trips (trip_id,user_id,trip_name,start_date,end_date)
                    VALUES (?,89000,'polling','2026-10-29','2026-10-29')
                    """, tripId);
            jdbc.update("INSERT INTO " + table
                            + " (execution_id,trip_id,user_id,total_attachment_count,status,display_status,"
                            + "process_published_at,result_raw_payload,ended_at) VALUES (?,?,89000,1,?,?,?,?,?)",
                    fixture.id(), tripId, fixture.status(), fixture.displayStatus(),
                    fixture.published() ? "2026-10-29 12:00:00" : null,
                    fixture.result() ? "{}" : null,
                    fixture.ended() ? "2026-10-29 12:01:00" : null);
        }

        List<String> selectedIds = creation
                ? creations.findAwaitingAiStatus().stream().map(execution -> execution.getExecutionId()).toList()
                : additions.findAwaitingAiStatus().stream().map(execution -> execution.getExecutionId()).toList();

        assertThat(selectedIds).containsExactly("ready");
    }

    private record Fixture(String id, String status, String displayStatus,
                           boolean published, boolean result, boolean ended) {
    }
}
