package com.yeodam.yeodambe.trip.performance;

import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Tag("performance")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("performance")
@Import(PostprocessLoadFixture.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostprocessUploadLoadTest {
    static final LocalAnalysisStub STUB=PostprocessLoadFixture.newStub();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {PostprocessLoadFixture.properties(r,STUB);}
    @MockitoBean AiEc2Starter starter;
    @Autowired PostprocessLoadFixture fixture;
    @Autowired StageMeasurements measurements;
    @Autowired MeterRegistry meters;
    @LocalServerPort int port;
    record Condition(String label,int n,int p,int u,int c,int h,LocalAnalysisStub.FaultMode fault,long slow,boolean sameUser,boolean polling) {}
    @AfterAll static void closeStub() {STUB.close();}
    @Test void runSelectedScenario() throws Exception {
        fixture.configure(port,STUB);fixture.requestTimeout(Duration.ofSeconds(600));
        String scenario=System.getProperty("load.scenario","S0"),mode=System.getProperty("load.mode","burst");
        int samples=positive("load.samples",100),repetitions=positive("load.repetitions",3);
        if(!Set.of("burst","arrival").contains(mode))throw new IllegalArgumentException("mode must be burst or arrival");
        if(mode.equals("arrival")&&!scenario.equals("S0"))throw new IllegalArgumentException("Arrival baseline uses S0; run controlled cases in burst mode");
        // Warmup uses fresh data and is excluded from measurement and historical counts.
        var warm=fixture.prepare(1,0,null);STUB.register(warm.tripId(),PostprocessUploadSmokeTest.spec(1,1));
        assertEquals(200,fixture.uploadFinal(warm,"warmup-"+UUID.randomUUID()).statusCode());fixture.cleanup();
        for(var condition:conditions(scenario))for(int rep=1;rep<=repetitions;rep++)run(condition,scenario,rep,samples,mode);
    }
    private void run(Condition c,String scenario,int repetition,int samples,String mode) throws Exception {
        String id=scenario+"-"+c.label()+"-r"+repetition+"-"+UUID.randomUUID();
        Map<String,Object> metadata=new LinkedHashMap<>(Map.of("scenario",scenario,"condition",c.label(),"N",c.n(),"P",c.p(),"U",c.u(),"C",c.c(),"H",c.h(),"repetition",repetition,"mode",mode));
        metadata.put("java_version",System.getProperty("java.version"));metadata.put("processors",Runtime.getRuntime().availableProcessors());
        metadata.put("shared_server_generator_jvm",true);metadata.put("warmup",1);metadata.put("kakao_timeout_ms",3000);metadata.put("kakao_concurrency",5);
        metadata.put("ai_delay_ms",100);metadata.put("kakao_delay_ms",100);metadata.put("slow_delay_ms",c.slow());metadata.put("fault",c.fault().name());
        metadata.put("resource_scope","measurement_windows_only");metadata.put("http_timeout_seconds",600);metadata.put("sql_scope","measurement_windows_including_auth_poll_observation");metadata.put("sample_target",samples);metadata.put("heap_limit",Runtime.getRuntime().maxMemory());
        try {var process=new ProcessBuilder("git","rev-parse","HEAD").start();metadata.put("revision",new String(process.getInputStream().readAllBytes()).trim());process.waitFor();}catch(Exception e){metadata.put("revision","unavailable");}
        List<LoadReport.JourneyResult> outcomes=new CopyOnWriteArrayList<>();
        List<LocalAnalysisStub.StubSnapshot> snapshots=new ArrayList<>();
        var fallback=new AtomicInteger();var pollingFailures=new AtomicInteger();var maxActive=new AtomicInteger();
        boolean safeToClean=true;
        try(var report=new LoadReport(Path.of("build/reports/postprocess-load",id))) {
            measurements.onSample(sample->{if(sample.errorType().equals("CannotCreateTransactionException")||sample.errorType().equals("SQLTransientConnectionException"))report.stop("database_connection_acquisition_timeout");});
            Map<String,Long> sqlDeltas=new LinkedHashMap<>();long measurementStart=System.nanoTime(),measurementNanos=0;
            try {
                if(mode.equals("arrival")) {
                    int rate=positive("load.ratePerMinute",1),seconds=positive("load.durationSeconds",300),capacity=positive("load.maxInFlight",20);
                    int count=(int)Math.ceil(rate*seconds/60.0);if(count>10000)throw new IllegalArgumentException("Too many planned arrivals");
                    List<PostprocessLoadFixture.TripFixture> fixtures=new ArrayList<>();
                    for(int i=0;i<count;i++){var f=fixture.prepare(c.n(),c.h(),null);fixture.uploadBeforeFinal(f);STUB.register(f.tripId(),spec(c,false));fixtures.add(f);}
                    Map<String,Long> sqlBefore=sqlSnapshot();
                    measurements.beginRun(id);report.observe(meters,fixture.jdbc,measurements::isActive);measurementStart=System.nanoTime();
                    try(var runner=new PostprocessLoadRunner(capacity,r->{outcomes.add(r);report.recordJourney(r);})) {
                        List<Callable<LoadReport.JourneyResult>> actions=fixtures.stream().<Callable<LoadReport.JourneyResult>>map(f->()->journey(f,c)).toList();
                        report.pendingSource(runner::active,true);runner.runArrivals(actions,rate,Duration.ofSeconds(seconds),()->report.shouldStop()||unexpectedErrorRate(outcomes));
                        runner.drain(Duration.ofSeconds(130));maxActive.set(runner.peak());fixture.awaitServerIdle(Duration.ofSeconds(130));
                        metadata.put("rate_per_minute",rate);metadata.put("planned_arrivals",count);metadata.put("schedule_lateness_max_ms",runner.maxLatenessNanos()/1e6);
                    } catch(Exception e){safeToClean=false;metadata.put("partial_window",true);throw e;}
                    finally {accumulateSql(sqlDeltas,sqlBefore,sqlSnapshot());measurements.endRun();}
                    measurementNanos=System.nanoTime()-measurementStart;
                    for(var f:fixtures){snapshots.add(STUB.snapshot(f.tripId()));if(fixture.databaseStatus(f.tripId()).equals("COMPLETED")){fixture.assertCompleted(f,c.p());assertEquals(c.u(),STUB.snapshot(f.tripId()).attempts());fallback.addAndGet(fixture.fallbackCount(f.tripId()));}}
                } else {
                    for(int from=0;from<samples;from+=c.c()) {
                        List<PostprocessLoadFixture.TripFixture> fixtures=new ArrayList<>();Long shared=null;
                        for(int i=0;i<Math.min(c.c(),samples-from);i++) {
                            var f=fixture.prepare(c.n(),c.h(),c.sameUser()?shared:null);if(shared==null)shared=f.userId();
                            fixture.uploadBeforeFinal(f);STUB.register(f.tripId(),spec(c,true));fixtures.add(f);
                        }
                        Map<String,Long> sqlBefore=sqlSnapshot();
                        measurements.beginRun(id);report.observe(meters,fixture.jdbc,measurements::isActive);measurementStart=System.nanoTime();
                        try(var runner=new PostprocessLoadRunner(c.c(),r->{outcomes.add(r);report.recordJourney(r);});
                            var pollExecutor=Executors.newVirtualThreadPerTaskExecutor();var pollClock=Executors.newSingleThreadScheduledExecutor()) {
                            report.pendingSource(runner::active);Set<Long> submitted=new HashSet<>();
                            try {
                                for(var f:fixtures) {
                                    runner.submit(()->journey(f,c));submitted.add(f.tripId());
                                    if(!STUB.awaitAnalysisRequests(Set.of(f.tripId()),Duration.ofSeconds(120)))throw new IllegalStateException("Final batch did not reach AI barrier");
                                }
                                if(c.polling())pollClock.scheduleAtFixedRate(()->fixtures.forEach(f->pollExecutor.submit(()->{try{fixture.poll(f);}catch(Exception|AssertionError e){pollingFailures.incrementAndGet();}})),0,2,TimeUnit.SECONDS);
                            } finally {STUB.releaseAnalysis(submitted);}
                            runner.drain(Duration.ofSeconds(130));fixture.awaitServerIdle(Duration.ofSeconds(130));maxActive.accumulateAndGet(runner.peak(),Math::max);
                            pollClock.shutdownNow();pollExecutor.shutdown();assertTrue(pollExecutor.awaitTermination(15,TimeUnit.SECONDS));
                        } catch(Exception e){safeToClean=false;metadata.put("partial_window",true);throw e;}
                    finally {accumulateSql(sqlDeltas,sqlBefore,sqlSnapshot());measurements.endRun();}
                        measurementNanos+=System.nanoTime()-measurementStart;
                        // Let timeout injection handlers finish after clients have abandoned the sockets.
                        if(c.fault()==LocalAnalysisStub.FaultMode.TIMEOUT_TWICE)Thread.sleep(1100);
                        var batchSamples=measurements.drain();
                        if(!c.sameUser() && outcomes.stream().filter(r->r.outcome().equals("success")).count()==outcomes.size())assertEquals(3L*(c.h()+c.n())*fixtures.size(),batchSamples.stream().filter(s->s.stage().equals("head")).mapToLong(StageMeasurements.StageSample::count).sum());
                        report.recordStages(batchSamples);measurements.endRun();
                        for(var f:fixtures) {var snapshot=STUB.snapshot(f.tripId());snapshots.add(snapshot);
                            if(fixture.databaseStatus(f.tripId()).equals("COMPLETED")) {
                                fixture.assertCompleted(f,c.p());int actualFallback=fixture.fallbackCount(f.tripId());fallback.addAndGet(actualFallback);
                                assertEquals(Set.of(LocalAnalysisStub.FaultMode.TIMEOUT_TWICE,LocalAnalysisStub.FaultMode.EMPTY_DOCUMENTS).contains(c.fault())?Math.max(1,c.u()/10):0,actualFallback);
                                int retries=Set.of(LocalAnalysisStub.FaultMode.FIRST_429,LocalAnalysisStub.FaultMode.FIRST_500,LocalAnalysisStub.FaultMode.TIMEOUT_TWICE).contains(c.fault())?Math.max(1,c.u()/10):0;
                                assertEquals(c.u()+retries,snapshot.attempts());
                            }
                        }
                        fixture.cleanup();
                        if(report.shouldStop()||unexpectedErrorRate(outcomes))break;
                    }
                }
            } finally {
                metadata.put("measurement_elapsed_ms",measurementNanos>0?measurementNanos/1e6:(System.nanoTime()-measurementStart)/1e6);
                report.recordStages(measurements.drain());measurements.endRun();measurements.onSample(s->{});
                metadata.put("kakao_attempts",snapshots.stream().mapToInt(LocalAnalysisStub.StubSnapshot::attempts).sum());
                metadata.put("kakao_retries",snapshots.stream().mapToInt(LocalAnalysisStub.StubSnapshot::retries).sum());
                metadata.put("kakao_peak_per_trip",snapshots.stream().mapToInt(LocalAnalysisStub.StubSnapshot::peakActive).max().orElse(0));
                var changes=new TreeMap<Long,Integer>();snapshots.forEach(snapshot->snapshot.events().forEach(event->{changes.merge(event.startNanos(),1,Integer::sum);changes.merge(event.endNanos(),-1,Integer::sum);}));
                int activeKakao=0,peakKakao=0;for(int change:changes.values()){activeKakao+=change;peakKakao=Math.max(peakKakao,activeKakao);}metadata.put("kakao_peak_global",peakKakao);
                metadata.put("fallback_places",fallback.get());metadata.put("polling_failures",pollingFailures.get());metadata.put("peak_active_journeys",maxActive.get());
                metadata.put("sql_digest_delta",sqlDeltas.isEmpty()?Map.of("availability","unavailable"):sqlDeltas);metadata.put("unfinished_server_uploads",measurements.activeUploads());metadata.put("cleanup_safe",safeToClean);
                if(measurements.activeUploads()>0)safeToClean=false;
                metadata.put("cleanup_safe",safeToClean);metadata.put("kakao_call_events",snapshots.stream().flatMap(snapshot->snapshot.events().stream()).toList());report.writeSummary(metadata);
                if(safeToClean)fixture.cleanup();
            }
        }
        assertFalse(outcomes.isEmpty());assertEquals(0,outcomes.stream().filter(r->Set.of("failure","timeout").contains(r.outcome())).count(),"See report "+id);
        assertEquals(0,pollingFailures.get(),"Polling failed");
    }
    private Map<String,Long> sqlSnapshot() {
        try {Map<String,Long> map=new LinkedHashMap<>();fixture.jdbc.query("select DIGEST,COUNT_STAR,SUM_TIMER_WAIT from performance_schema.events_statements_summary_by_digest where SCHEMA_NAME='yeodam'",rs->{map.put(rs.getString(1)+"_count",rs.getLong(2));map.put(rs.getString(1)+"_time_ps",rs.getLong(3));});return map;}
        catch(RuntimeException e){return Map.of();}
    }
    private void accumulateSql(Map<String,Long> delta,Map<String,Long> before,Map<String,Long> after) {after.forEach((k,v)->delta.merge(k,Math.max(0,v-before.getOrDefault(k,0L)),Long::sum));}
    private boolean unexpectedErrorRate(List<LoadReport.JourneyResult> outcomes) {return !outcomes.isEmpty()&&outcomes.stream().filter(r->Set.of("failure","timeout").contains(r.outcome())).count()/(double)outcomes.size()>.01;}
    private LoadReport.JourneyResult journey(PostprocessLoadFixture.TripFixture f,Condition c) {
        long started=System.nanoTime();String request="load-"+UUID.randomUUID();
        try {var response=fixture.uploadFinal(f,request);String status=new ObjectMapper().readTree(response.body()).path("data").path("status").asString();
            return new LoadReport.JourneyResult(f.tripId(),request,System.nanoTime()-started,response.statusCode(),status,response.statusCode()==200&&status.equals("COMPLETED")?"success":"failure");
        } catch(Exception e) {return new LoadReport.JourneyResult(f.tripId(),request,System.nanoTime()-started,0,e.getClass().getSimpleName(),e instanceof java.net.http.HttpTimeoutException?"timeout":"failure");}
    }
    private LocalAnalysisStub.RunSpec spec(Condition c,boolean hold) {return new LocalAnalysisStub.RunSpec(c.p(),c.u(),c.fault(),100,100,c.slow(),hold);}
    private static int positive(String name,int fallback) {int v=Integer.parseInt(System.getProperty(name,""+fallback));if(v<1)throw new IllegalArgumentException(name+" must be positive");return v;}
    private List<Condition> conditions(String s) {
        List<Condition> result=new ArrayList<>();
        switch(s) {
            case "S0" -> result.add(c("baseline",50,10,10,1,0));
            case "S1" -> {for(int p:new int[]{1,5,6,10,20,50})result.add(c("P"+p,50,p,p,1,0));}
            case "S2" -> {for(int u:new int[]{1,5,20})result.add(c("U"+u,50,20,u,1,0));}
            case "S3" -> {result.add(c("uniform",50,20,20,1,0));result.add(new Condition("slow",50,20,20,1,0,LocalAnalysisStub.FaultMode.NONE,2000,false,false));}
            case "S4" -> {for(var fault:LocalAnalysisStub.FaultMode.values())if(fault!=LocalAnalysisStub.FaultMode.NONE)result.add(new Condition(fault.name(),50,20,20,1,0,fault,0,false,false));}
            case "S5" -> {for(int c:new int[]{1,2,5,10,20})result.add(c("C"+c,50,10,10,c,0));}
            case "S6" -> {for(int h:new int[]{0,200,1000})result.add(c("H"+h,50,10,10,1,h));}
            case "S7" -> {result.add(c("independent",50,10,10,5,0));result.add(new Condition("same-user",50,10,10,5,0,LocalAnalysisStub.FaultMode.NONE,0,true,false));}
            case "S8" -> {for(int n:new int[]{10,50,100,200})result.add(c("N"+n,n,10,10,1,0));}
            case "S9" -> {result.add(c("without-poll",50,10,10,2,0));result.add(new Condition("with-poll",50,10,10,2,0,LocalAnalysisStub.FaultMode.NONE,0,false,true));}
            default -> throw new IllegalArgumentException("Unknown scenario "+s);
        }
        return result;
    }
    private Condition c(String label,int n,int p,int u,int concurrency,int h) {return new Condition(label,n,p,u,concurrency,h,LocalAnalysisStub.FaultMode.NONE,0,false,false);}
}
