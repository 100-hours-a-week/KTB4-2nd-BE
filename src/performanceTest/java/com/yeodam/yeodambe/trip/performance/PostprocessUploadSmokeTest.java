package com.yeodam.yeodambe.trip.performance;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("performance")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("performance")
@Import(PostprocessLoadFixture.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostprocessUploadSmokeTest {
    static final LocalAnalysisStub STUB=PostprocessLoadFixture.newStub();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {PostprocessLoadFixture.properties(r,STUB);}
    @MockitoBean AiEc2Starter starter;
    @Autowired PostprocessLoadFixture fixture;
    @Autowired StageMeasurements measurements;
    @LocalServerPort int port;
    @BeforeEach void setup() {fixture.configure(port,STUB);}
    @AfterEach void cleanup() {measurements.onSample(s->{});measurements.endRun();fixture.cleanup();}
    @AfterAll static void close() {STUB.close();}
    @Test void realHttpUploadCompletes() throws Exception {
        var f=fixture.prepare(1,0,null);STUB.register(f.tripId(),spec(1,1));
        var result=fixture.uploadFinal(f,"smoke-"+UUID.randomUUID());
        assertEquals(200,result.statusCode());fixture.assertCompleted(f,1);
    }
    @Test void missingAccessCookieIsUnauthorized() throws Exception {
        var f=fixture.prepare(1,0,null);
        assertEquals(401,fixture.uploadBatch(f,1,true,"auth-check",false,true).statusCode());
        assertEquals(403,fixture.uploadBatch(f,1,true,"csrf-check",true,false).statusCode());
    }
    @Test void historicalFixtureHasRealObjects() throws Exception {
        var f=fixture.prepare(1,2,null);assertEquals(6,fixture.historyKeys().size());fixture.validateHistory();
        String key=fixture.historyKeys().getFirst();fixture.deleteObject(key);
        assertThrows(RuntimeException.class,fixture::validateHistory);
    }
    @Test void saveSampleIsPublishedAfterCommit() throws Exception {
        var f=fixture.prepare(1,0,null);STUB.register(f.tripId(),spec(1,1));measurements.beginRun("commit");
        var seen=new java.util.concurrent.atomic.AtomicBoolean();
        measurements.onSample(s->{if(s.stage().equals("save")&&s.outcome().equals("success")) {
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                try {assertEquals("COMPLETED",executor.submit(()->fixture.databaseStatus(f.tripId())).get(5,TimeUnit.SECONDS));seen.set(true);}
                catch(Exception e) {throw new IllegalStateException(e);}
            }
        }});
        assertEquals(200,fixture.uploadFinal(f,"commit-"+UUID.randomUUID()).statusCode());
        assertTrue(seen.get());var samples=measurements.drain();
        assertEquals(3,samples.stream().filter(s->s.stage().equals("head")).mapToLong(StageMeasurements.StageSample::count).sum());
        assertEquals(1,StageMeasurements.postprocessNanos(samples).size());
    }
    @Test void duplicateCoordinatesReduceCalls() throws Exception {
        var f=fixture.prepare(6,0,null);STUB.register(f.tripId(),spec(6,1));
        assertEquals(200,fixture.uploadFinal(f,"dedup-"+UUID.randomUUID()).statusCode());fixture.assertCompleted(f,6);
        assertEquals(1,STUB.snapshot(f.tripId()).attempts());
    }
    @Test void retryAndFallbackCountsMatch() throws Exception {
        var f=fixture.prepare(20,0,null);
        STUB.register(f.tripId(),new LocalAnalysisStub.RunSpec(20,20,LocalAnalysisStub.FaultMode.FIRST_429,0,0,0,false));
        fixture.uploadBeforeFinal(f);assertEquals(200,fixture.uploadFinal(f,"retry-"+UUID.randomUUID()).statusCode());
        assertEquals(22,STUB.snapshot(f.tripId()).attempts());fixture.assertCompleted(f,20);
    }
    @Test void pollingContinuesWhilePostIsBlocked() throws Exception {
        var f=fixture.prepare(1,0,null);STUB.register(f.tripId(),new LocalAnalysisStub.RunSpec(1,1,LocalAnalysisStub.FaultMode.NONE,0,0,0,true));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->fixture.uploadFinal(f,"poll-"+UUID.randomUUID()));
            try {assertTrue(STUB.awaitAnalysisRequests(Set.of(f.tripId()),Duration.ofSeconds(30)));
                assertEquals("PROCESSING",fixture.poll(f));assertFalse(pending.isDone());
            } finally {STUB.releaseAnalysis(Set.of(f.tripId()));}
            assertEquals(200,pending.get(30,TimeUnit.SECONDS).statusCode());
        }
    }
    @Test void baselineTenJourneys() throws Exception {
        String run="smoke-S0-"+UUID.randomUUID();long elapsed=0;
        try(var report=new LoadReport(java.nio.file.Path.of("build/reports/postprocess-load",run))) {
            for(int i=0;i<10;i++) {
                var f=fixture.prepare(50,0,null);fixture.uploadBeforeFinal(f);
                STUB.register(f.tripId(),new LocalAnalysisStub.RunSpec(10,10,LocalAnalysisStub.FaultMode.NONE,100,100,0,false));
                measurements.beginRun(run);String request="smoke-load-"+UUID.randomUUID();long start=System.nanoTime();
                var response=fixture.uploadFinal(f,request);long duration=System.nanoTime()-start;elapsed+=duration;
                report.recordJourney(new LoadReport.JourneyResult(f.tripId(),request,duration,response.statusCode(),"COMPLETED",response.statusCode()==200?"success":"failure"));
                var samples=measurements.drain();measurements.endRun();report.recordStages(samples);
                assertEquals(200,response.statusCode());fixture.assertCompleted(f,10);assertEquals(10,STUB.snapshot(f.tripId()).attempts());
                assertEquals(150,samples.stream().filter(sample->sample.stage().equals("head")).mapToLong(StageMeasurements.StageSample::count).sum());fixture.cleanup();
            }
            report.writeSummary(Map.of("scenario","S0-smoke","N",50,"P",10,"U",10,"C",1,"H",0,"measurement_elapsed_ms",elapsed/1e6,"shared_server_generator_jvm",true));
        }
    }
    static LocalAnalysisStub.RunSpec spec(int p,int u) {return new LocalAnalysisStub.RunSpec(p,u,LocalAnalysisStub.FaultMode.NONE,0,0,0,false);}
}
