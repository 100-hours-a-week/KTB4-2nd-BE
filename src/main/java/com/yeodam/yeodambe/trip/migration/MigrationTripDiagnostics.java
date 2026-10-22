package com.yeodam.yeodambe.trip.migration;

import java.util.Map;
import java.util.LinkedHashMap;

/** Request-local measurements only. No SQL, payload, key or credential is retained. */
final class MigrationTripDiagnostics {
    static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    static boolean enabled() {
        return Boolean.parseBoolean(System.getenv("MIGRATION_DIAGNOSTICS_ENABLED"));
    }
    static final class Trace {
        final long receivedMs, schedulerWaitUs;
        final Map<String, Long> micros = new LinkedHashMap<>();
        final Map<String, Long> counts = new LinkedHashMap<>();
        long applyStartNs, bodyEntryNs;
        Trace(long receivedMs, long submittedNs) {
            this.receivedMs = receivedMs;
            schedulerWaitUs = Math.max(0, (System.nanoTime() - submittedNs) / 1000);
        }
        void add(String kind, long nanos) {
            micros.merge(kind, Math.max(0, nanos / 1000), Long::sum);
            counts.merge(kind, 1L, Long::sum);
        }
        String fields() {
            var out = new StringBuilder("received_ms=").append(receivedMs)
                    .append(" scheduler_wait_us=").append(schedulerWaitUs)
                    .append(" tx_entry_us=").append(bodyEntryNs > 0 && applyStartNs > 0 ? Math.max(0,(bodyEntryNs-applyStartNs)/1000) : -1);
            for (String k : new String[]{"connection", "journal_read", "trip_lookup", "trip_write", "region_write", "journal_write", "other_sql", "commit", "rollback"}) {
                out.append(' ').append(k).append("_us=").append(micros.getOrDefault(k,0L));
                out.append(' ').append(k).append("_count=").append(counts.getOrDefault(k,0L));
            }
            return out.toString();
        }
    }
    static void record(String kind, long since) {
        var t=CURRENT.get(); if(t!=null) t.add(kind,System.nanoTime()-since);
    }
    static String sqlKind(String sql) {
        // Classification only; neither the statement nor its parameters are logged.
        String s=sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
        if(s.startsWith("select") && s.contains("migration_trip_request_result")) return "journal_read";
        if(s.startsWith("select") && s.contains("trips")) return "trip_lookup";
        if(s.startsWith("insert") && s.contains("migration_trip_request_result")) return "journal_write";
        if(s.startsWith("insert") && s.contains("trip_regions")) return "region_write";
        if(s.startsWith("insert") && s.contains("trips")) return "trip_write";
        return "other_sql";
    }
}
