package com.yeodam.yeodambe.trip.migration;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class MigrationTripDiagnosticsTest {
    @Test void jdbcTimingPreservesCommitRollbackAndNeverIncludesValues() throws Exception {
        var ds=new DriverManagerDataSource();ds.setUrl("jdbc:h2:mem:diag396;DB_CLOSE_DELAY=-1");
        var wrapped=MigrationDiagnosticsDataSource.wrap(ds);
        var jdbc=new JdbcTemplate(wrapped);
        jdbc.execute("CREATE TABLE IF NOT EXISTS trips (id INT PRIMARY KEY, name VARCHAR(100))");
        var trace=new MigrationTripDiagnostics.Trace(1000,System.nanoTime());
        MigrationTripDiagnostics.CURRENT.set(trace);
        try(var c=wrapped.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO trips VALUES (?,?)")) {
                s.setInt(1,1);s.setString(2,"PRIVATE_BOUND_VALUE");s.executeUpdate();
            }
            c.commit();
            try(var s=c.prepareStatement("INSERT INTO trips VALUES (?,?)")) {
                s.setInt(1,2);s.setString(2,"PRIVATE_BOUND_VALUE");s.executeUpdate();
            }
            c.rollback();
            assertThat(trace.counts.get("trip_write")).isEqualTo(2);
            assertThat(trace.counts.get("connection")).isEqualTo(1);
            assertThat(trace.counts.get("commit")).isEqualTo(1);
            assertThat(trace.counts.get("rollback")).isEqualTo(1);
            assertThat(trace.fields()).doesNotContain("PRIVATE_BOUND_VALUE","INSERT","SELECT");
        } finally {MigrationTripDiagnostics.CURRENT.remove();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips",Integer.class)).isEqualTo(1);
    }
    @Test void failedSqlStillThrowsOriginalSQLExceptionAndIsMeasured() throws Exception {
        var ds=new DriverManagerDataSource();ds.setUrl("jdbc:h2:mem:diag396failure");
        var trace=new MigrationTripDiagnostics.Trace(1000,System.nanoTime());
        MigrationTripDiagnostics.CURRENT.set(trace);
        try(var c=MigrationDiagnosticsDataSource.wrap(ds).getConnection();var s=c.prepareStatement("SELECT CAST(? AS INTEGER)")) {
            s.setString(1,"not-an-integer");
            assertThatThrownBy(s::executeQuery).isInstanceOf(java.sql.SQLException.class);
            assertThat(trace.counts.get("other_sql")).isEqualTo(1);
        } finally {MigrationTripDiagnostics.CURRENT.remove();}
    }
}
