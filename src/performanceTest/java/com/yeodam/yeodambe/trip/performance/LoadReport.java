package com.yeodam.yeodambe.trip.performance;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

final class LoadReport implements AutoCloseable {
    record JourneyResult(long tripId,String requestId,long durationNanos,int httpStatus,String status,String outcome) {}
    private final Path directory;
    private final List<JourneyResult> journeys=new CopyOnWriteArrayList<>();
    private final List<StageMeasurements.StageSample> stages=new ArrayList<>();
    private final List<Map<String,Object>> resources=new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
    private boolean observing;
    private final java.util.concurrent.atomic.AtomicInteger pendingIncrease=new java.util.concurrent.atomic.AtomicInteger();
    private int lastPending;
    private volatile java.util.function.IntSupplier pending=()->0;
    private volatile boolean stopOnGrowth;
    void pendingSource(java.util.function.IntSupplier supplier){pendingSource(supplier,false);}
    void pendingSource(java.util.function.IntSupplier supplier,boolean stopOnGrowth){pending=supplier;this.stopOnGrowth=stopOnGrowth;lastPending=0;pendingIncrease.set(0);}
    void stop(String reason){stop=true;stopReason=reason;}
    private volatile boolean stop;
    private volatile String stopReason="";
    private final long started=System.nanoTime();
    LoadReport(Path directory) throws IOException {this.directory=directory;Files.createDirectories(directory);if(Files.exists(directory.resolve("summary.json")))throw new IOException("Report already exists");}
    void recordJourney(JourneyResult result) {journeys.add(result);}
    void recordStages(List<StageMeasurements.StageSample> values) {stages.addAll(values);}
    boolean shouldStop() {return stop;}
    String stopReason() {return stopReason;}
    synchronized void observe(MeterRegistry meters,JdbcTemplate jdbc,java.util.function.BooleanSupplier active) {
        if(observing)return;observing=true;
        monitor.scheduleAtFixedRate(()->{
            if(!active.getAsBoolean())return;
            int current=pending.getAsInt();if(current>lastPending)pendingIncrease.incrementAndGet();else pendingIncrease.set(0);lastPending=current;
            if(stopOnGrowth && pendingIncrease.get()>=3)stop("pending_growing_three_samples");
            Map<String,Object> row=new LinkedHashMap<>();row.put("pending_journeys",current);
            row.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);
            var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            row.put("heap_used",heap.getUsed());row.put("heap_max",heap.getMax());
            row.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());
            row.put("gc_ms",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionTime())).sum());
            var os=ManagementFactory.getOperatingSystemMXBean();
            row.put("process_cpu",os instanceof com.sun.management.OperatingSystemMXBean o ? o.getProcessCpuLoad():-1);
            for(String name:List.of("hikaricp.connections.active","hikaricp.connections.pending")) {
                var gauge=meters.find(name).gauge();row.put(name,gauge==null?"unavailable":gauge.value());
            }
            try {row.put("lock_waits",jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits",Long.class));}
            catch(RuntimeException e) {row.put("lock_waits","unavailable");}
            resources.add(row);
            if(heap.getMax()>0 && heap.getUsed()>.9*heap.getMax()) {stop=true;stopReason="heap_90_percent";}
        },0,1,TimeUnit.SECONDS);
    }
    static Long percentile(List<Long> input,double q) {
        if(input.isEmpty())return null;
        var sorted=input.stream().sorted().toList();return sorted.get(Math.max(0,(int)Math.ceil(q*sorted.size())-1));
    }
    static Map<String,Object> journeySummary(List<JourneyResult> results) {
        Map<String,Object> map=new LinkedHashMap<>();map.put("attempted",results.size());
        for(String outcome:List.of("success","failure","timeout","drop"))map.put(outcome,results.stream().filter(r->r.outcome().equals(outcome)).count());
        map.put("insufficient_samples",results.stream().filter(r->r.outcome().equals("success")).count()<100);return map;
    }
    void writeSummary(Map<String,Object> metadata) throws IOException {
        monitor.shutdownNow();
        Map<String,Object> summary=new LinkedHashMap<>(metadata);summary.putAll(journeySummary(journeys));
        summary.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);summary.put("stop_reason",stopReason);
        summary.put("completed_per_min",journeys.stream().filter(r->r.outcome().equals("success")).count()*60000.0/((Number)metadata.getOrDefault("measurement_elapsed_ms",(System.nanoTime()-started)/1e6)).doubleValue());
        Map<String,Object> timings=new LinkedHashMap<>();
        for(String stage:List.of("ai","resolve","save","stats","head","head_max")) {
            var matching=stages.stream().filter(s->s.stage().equals(stage)).toList();
            timings.put(stage,distribution(matching.stream().filter(s->s.outcome().equals("success")).map(s->s.endNanos()-s.startNanos()).toList()));
            summary.put(stage+"_count",matching.stream().mapToLong(StageMeasurements.StageSample::count).sum());
        }
        timings.put("postprocess",distribution(new ArrayList<>(StageMeasurements.postprocessNanos(stages).values())));
        timings.put("http",distribution(journeys.stream().filter(r->r.outcome().equals("success")).map(JourneyResult::durationNanos).toList()));
        summary.put("timings",timings);
        ObjectMapper json=new ObjectMapper();
        Files.writeString(directory.resolve("summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        StringBuilder csv=new StringBuilder("trip_id,request_id,http_ms,http_status,status,outcome\n");
        for(var r:journeys)csv.append(r.tripId()).append(',').append(r.requestId()).append(',').append(r.durationNanos()/1e6).append(',').append(r.httpStatus()).append(',').append(r.status()).append(',').append(r.outcome()).append('\n');
        Files.writeString(directory.resolve("journeys.csv"),csv);
        csv=new StringBuilder("request_id,trip_id,stage,start_ns,end_ns,outcome,error_type,count\n");
        for(var s:stages)csv.append(s.requestId()).append(',').append(s.tripId()).append(',').append(s.stage()).append(',').append(s.startNanos()).append(',').append(s.endNanos()).append(',').append(s.outcome()).append(',').append(s.errorType()).append(',').append(s.count()).append('\n');
        Files.writeString(directory.resolve("samples.csv"),csv);
        Files.writeString(directory.resolve("resources.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(resources));
        if(!resources.isEmpty()) {var fields=new ArrayList<>(resources.getFirst().keySet());var resourceCsv=new StringBuilder(String.join(",",fields)+"\n");for(var row:resources){resourceCsv.append(String.join(",",fields.stream().map(field->String.valueOf(row.getOrDefault(field,"unavailable"))).toList())).append("\n");}Files.writeString(directory.resolve("resources.csv"),resourceCsv);}
    }
    private static Map<String,Object> distribution(List<Long> values) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("samples",values.size());
        for(var entry:Map.of("p50",.5,"p95",.95,"max",1.0).entrySet()) {Long ns=percentile(values,entry.getValue());r.put(entry.getKey()+"_ms",ns==null?null:ns/1e6);}
        return r;
    }
    @Override public void close() {monitor.shutdownNow();}
}
