package com.yeodam.yeodambe.trip.performance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("performance")
@Import({PostprocessLoadFixture.Config.class,DerivativeK6ServerTest.Config.class})
class DerivativeK6PipelineTest {
    static final LocalAnalysisStub STUB = PostprocessLoadFixture.newStub();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { PostprocessLoadFixture.properties(r, STUB); }
    @MockitoBean AiEc2Starter starter;
    @Autowired PostprocessLoadFixture fixture;
    @Autowired StageMeasurements measurements;
    @LocalServerPort int port;
    @AfterAll static void close() { STUB.close(); }

    @Test void finalBatchConnectsOriginalsWorkerAndAiReceipt() throws Exception { checkPipeline(1,0); }
    @Test void intermediateBatchDoesNotStartAiAndReadyDelayIsMeasured() throws Exception { checkPipeline(11,200); }
    @Test void inactiveTraceDoesNotEmitPipelineEvents() throws Exception {
        if (System.getProperty("load.config") != null) return;
        checkPipeline(1,-1);
    }
    @Test void failedIoDoesNotReachAiAndFollowingBatchRecovers() throws Exception {
        if (System.getProperty("load.config") != null) return;
        fixture.configure(port, STUB);
        Path root=Files.createTempDirectory("pipeline-failure-");
        var appender=new DerivativeK6ServerTest.EventAppender(root.resolve("events.jsonl"));
        Logger logger=(Logger)LoggerFactory.getLogger("com.yeodam.yeodambe");
        String previousRun=System.getProperty("load.runDir"),previousConfig=System.getProperty("load.config");
        appender.start();logger.addAppender(appender);
        try {
            System.setProperty("load.runDir",root.toString());
            measurements.beginRun("failure");DerivativeK6ServerTest.observePipeline(measurements,STUB);
            for(String stage:List.of("original_read","put_preview")) {
                Path config=root.resolve("config.json");
                DerivativeK6ServerTest.write(config,Map.of("fault",Map.of("stage",stage,"kind","error","photo_index",2)));
                System.setProperty("load.config",config.toString());
                var failed=fixture.prepare(5,0,null);STUB.register(failed.tripId(),PostprocessUploadSmokeTest.spec(1,1));
                String request="failed-"+stage;
                assertEquals(500,fixture.uploadFinal(failed,request).statusCode());
                assertEquals("PROCESSING",fixture.databaseStatus(failed.tripId()));
                assertEquals(0,fixture.jdbc.queryForObject("select count(*) from trip_attachments where trip_id=?",Integer.class,failed.tripId()));
                var events=Files.readAllLines(root.resolve("events.jsonl")).stream().map(DerivativeK6ServerTest.JSON::readTree)
                    .filter(e->e.path("batch_id").asString().equals(request)).toList();
                assertTrue(events.stream().anyMatch(e->e.path("stage").asString().equals("end")&&e.path("outcome").asString().equals("failure")));
                assertTrue(events.stream().noneMatch(e->e.path("stage").asString().startsWith("ai_request")));
                var recovery=fixture.prepare(1,0,null);STUB.register(recovery.tripId(),PostprocessUploadSmokeTest.spec(1,1));
                assertEquals(200,fixture.uploadFinal(recovery,"recovery-"+stage).statusCode());
                fixture.assertCompleted(recovery,1);
            }
        } finally {
            measurements.endRun();measurements.onSample(sample -> {});STUB.onAnalysisReceived(receipt -> {});
            fixture.awaitServerIdle(Duration.ofSeconds(30));fixture.cleanup();
            if(previousRun==null)System.clearProperty("load.runDir");else System.setProperty("load.runDir",previousRun);
            if(previousConfig==null)System.clearProperty("load.config");else System.setProperty("load.config",previousConfig);
            logger.detachAppender(appender);appender.stop();
        }
    }

    @Test void rejectedQueuedBatchDoesNotReachAiAndWorkerRecovers() throws Exception {
        if (System.getProperty("load.config") != null) return;
        fixture.configure(port,STUB);
        Path root=Files.createTempDirectory("pipeline-queue-");
        var appender=new DerivativeK6ServerTest.EventAppender(root.resolve("events.jsonl"));
        Logger logger=(Logger)LoggerFactory.getLogger("com.yeodam.yeodambe");
        String previousRun=System.getProperty("load.runDir"),previousConfig=System.getProperty("load.config");
        appender.start();logger.addAppender(appender);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            System.setProperty("load.runDir",root.toString());
            Path config=root.resolve("config.json");
            DerivativeK6ServerTest.write(config,Map.of("fault",Map.of("stage","original_read","kind","delay","photo_index",0,"delay_ms",3500)));
            System.setProperty("load.config",config.toString());
            measurements.beginRun("queue");DerivativeK6ServerTest.observePipeline(measurements,STUB);
            var fixtures=new ArrayList<PostprocessLoadFixture.TripFixture>();
            for(int i=0;i<5;i++) {
                var f=fixture.prepare(1,0,null);fixtures.add(f);STUB.register(f.tripId(),PostprocessUploadSmokeTest.spec(1,1));
            }
            var pending=new ArrayList<java.util.concurrent.Future<java.net.http.HttpResponse<String>>>();
            for(int i=0;i<3;i++) {
                int index=i;String request="queue-"+i;
                pending.add(executor.submit(()->fixture.uploadFinal(fixtures.get(index),request)));
                awaitStage(root,request,i==0?"start":"submit");
            }
            assertEquals(500,fixture.uploadFinal(fixtures.get(3),"queue-rejected").statusCode());
            awaitStage(root,"queue-rejected","rejected");
            var rejected=Files.readAllLines(root.resolve("events.jsonl")).stream().map(DerivativeK6ServerTest.JSON::readTree)
                .filter(e->e.path("batch_id").asString().equals("queue-rejected")).toList();
            assertTrue(rejected.stream().noneMatch(e->e.path("stage").asString().startsWith("ai_request")));
            for(var future:pending)assertEquals(200,future.get(30,TimeUnit.SECONDS).statusCode());
            assertEquals(200,fixture.uploadFinal(fixtures.get(4),"queue-recovery").statusCode());
            fixture.assertCompleted(fixtures.get(4),1);
        } finally {
            executor.close();measurements.endRun();measurements.onSample(sample -> {});STUB.onAnalysisReceived(receipt -> {});
            fixture.awaitServerIdle(Duration.ofSeconds(30));fixture.cleanup();
            if(previousRun==null)System.clearProperty("load.runDir");else System.setProperty("load.runDir",previousRun);
            if(previousConfig==null)System.clearProperty("load.config");else System.setProperty("load.config",previousConfig);
            logger.detachAppender(appender);appender.stop();
        }
    }
    private void awaitStage(Path root,String request,String stage) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            boolean found=Files.readAllLines(root.resolve("events.jsonl")).stream()
                .anyMatch(line->line.contains("\"batch_id\":\""+request+"\"")&&line.contains("\"stage\":\""+stage+"\""));
            if(found)return;
            Thread.sleep(10);
        }
        fail("Missing " + stage + " for " + request);
    }

    private void checkPipeline(int photos,long delay) throws Exception {
        if (System.getProperty("load.config") != null) return;
        fixture.configure(port, STUB);
        Path root=Files.createTempDirectory("pipeline-");
        var appender=new DerivativeK6ServerTest.EventAppender(root.resolve("events.jsonl"));
        Logger logger=(Logger)LoggerFactory.getLogger("com.yeodam.yeodambe");
        String previous=System.getProperty("load.runDir");
        appender.start();logger.addAppender(appender);
        try {
            if(delay>=0)System.setProperty("load.runDir",root.toString());else System.clearProperty("load.runDir");
            measurements.beginRun("pipeline");DerivativeK6ServerTest.observePipeline(measurements,STUB);
            var f=fixture.prepare(photos,0,null);
            STUB.register(f.tripId(),PostprocessUploadSmokeTest.spec(1,1));
            if(photos>10) {
                assertEquals(204,fixture.uploadBatch(f,1,false,"pipeline-middle",true,true).statusCode());
                assertFalse(Files.readString(root.resolve("events.jsonl")).contains("ai_request_start"));
            }
            STUB.setReadyDelay(Math.max(0,delay));
            assertEquals(200,fixture.uploadFinal(f,"pipeline-final").statusCode());
            fixture.assertCompleted(f,1);
            var events=Files.readAllLines(root.resolve("events.jsonl")).stream().map(DerivativeK6ServerTest.JSON::readTree)
                .filter(e->e.path("batch_id").asString().equals("pipeline-final")).toList();
            if(delay<0) {
                assertTrue(events.stream().noneMatch(e->e.path("stage").asString().equals("originals_saved")));
                return;
            }
            long last=0;
            for(String stage:List.of("originals_saved","submit","start","end","ai_health_ready","ai_request_start","ai_request_received")) {
                var matches=events.stream().filter(e->e.path("stage").asString().equals(stage)).toList();
                assertEquals(1,matches.size(),stage);
                long end=matches.getFirst().path("end_ns").asLong(); assertTrue(end>=last,stage); last=end;
            }
            org.mockito.Mockito.verify(starter).ensureRunning();
        } finally {
            measurements.endRun();measurements.onSample(sample -> {});STUB.onAnalysisReceived(receipt -> {});
            STUB.setReadyDelay(0);fixture.awaitServerIdle(Duration.ofSeconds(30));fixture.cleanup();
            if(previous==null)System.clearProperty("load.runDir");else System.setProperty("load.runDir",previous);
            logger.detachAppender(appender);appender.stop();
        }
    }
}
