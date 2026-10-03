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
@Import({PostprocessLoadFixture.Config.class, DerivativeK6ServerTest.Config.class})
class DerivativeK6ServerTest {
    static final LocalAnalysisStub STUB = PostprocessLoadFixture.newStub();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String,List<Stored>> STORED = new ConcurrentHashMap<>();
    record Stored(String key, String kind, String hash, int photoIndex) {}
    static JsonNode config() {
        String path = System.getProperty("load.config");
        return path == null ? JSON.createObjectNode() : JSON.readTree(Path.of(path).toFile());
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        PostprocessLoadFixture.properties(r,STUB);
        if (System.getProperty("load.runDir") != null) {
            r.add("server.port", () -> 18080);
            r.add("auth.jwt.access-token-ttl", () -> config().path("token_ttl_seconds").asLong() + "s");
        }
    }
    @MockitoBean AiEc2Starter starter;
    @Autowired PostprocessLoadFixture fixture;
    @Autowired StageMeasurements measurements;
    @Autowired MeterRegistry registry;
    @Autowired TripAttachmentStorageClient storage;
    @LocalServerPort int port;
    @AfterAll static void close() {STUB.close();}

    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean static StorageProbe storageProbe() {return new StorageProbe();}
    }
    static class StorageProbe implements BeanPostProcessor, Ordered {
        @Override public int getOrder() {return Ordered.LOWEST_PRECEDENCE;}
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!name.equals("s3TripAttachmentStorageClient")) return bean;
            MethodInterceptor advice = invocation -> {
                String method = invocation.getMethod().getName();
                String request = MDC.get("request_id");
                if (request == null || System.getProperty("load.runDir") == null) return invocation.proceed();
                JsonNode fault = config().path("fault");
                String stage = method.equals("open") ? "original_read" : method.equals("storeDerived")
                        ? "put_" + ((Path) invocation.getArguments()[1]).getFileName().toString().split("\\.")[0] : "";
                String photoIndex = MDC.get("worker_photo_index");
                if (!stage.isEmpty() && photoIndex != null && !request.contains("warmup") && !request.contains("recovery")
                        && stage.equals(fault.path("stage").asString())
                        && Integer.parseInt(photoIndex) == fault.path("photo_index").asInt(-1)) {
                    long started = System.nanoTime();
                    if (fault.path("kind").asString().equals("delay")) Thread.sleep(fault.path("delay_ms").asLong());
                    Map<String,Object> event = Map.of("batch_id",request,"stage","injection", "photo_index",Integer.parseInt(photoIndex),
                            "start_ns",started,"end_ns",System.nanoTime(),"epoch_ms",System.currentTimeMillis(),
                            "outcome",fault.path("kind").asString(),"photo_count",1,"injected_stage",stage);
                    LoggerFactory.getLogger(DerivativeK6ServerTest.class).atInfo().addKeyValue("event","image_worker")
                            .addKeyValue("worker",event).log("테스트 I/O 장애 주입");
                    if (fault.path("kind").asString().equals("error")) throw new IllegalStateException("Injected " + stage + " failure");
                }
                Object result = invocation.proceed();
                if (method.equals("store") || method.equals("storeDerived")) {
                    String hash = "";
                    if (method.equals("store")) {
                        try (InputStream stream=((MultipartFile)invocation.getArguments()[1]).getInputStream()) {hash=hash(stream);}
                    }
                    List<Stored> objects=STORED.computeIfAbsent(request,k->new CopyOnWriteArrayList<>());
                    int index=method.equals("store") ? (int)objects.stream().filter(s->s.kind().equals("original")).count() : Integer.parseInt(photoIndex);
                    objects.add(new Stored((String)result,method.equals("store")?"original":stage.substring(4),hash,index));
                }
                return result;
            };
            if (bean instanceof Advised advised) {advised.addAdvice(0,advice);return bean;}
            ProxyFactory proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);proxy.addAdvice(advice);return proxy.getProxy();
        }
    }

    @Test void exportsIndependentAuthenticatedFixtures() throws Exception {
        if (System.getProperty("load.runDir") != null) return;
        fixture.configure(port, STUB);
        Path target = Files.createTempFile("fixtures", ".json");
        try {
            var one = fixture.prepare(1, 0, null); var two = fixture.prepare(1, 0, null);
            STUB.register(one.tripId(),PostprocessUploadSmokeTest.spec(1,1));
            STUB.register(two.tripId(),PostprocessUploadSmokeTest.spec(1,1));
            fixture.export(target,List.of(one,two));
            var values=JSON.readTree(target.toFile());
            assertEquals(2,values.size());
            assertNotEquals(values.get(0).path("trip_id").asLong(),values.get(1).path("trip_id").asLong());
            assertTrue(values.get(0).path("cookie").asString().contains("CSRF_CONTEXT="));
            assertFalse(values.get(0).path("csrf").asString().isBlank());
            assertEquals(200,fixture.uploadFinal(one,"export-one").statusCode());
            assertEquals(200,fixture.uploadFinal(two,"export-two").statusCode());
            fixture.assertCompleted(one,1);fixture.assertCompleted(two,1);
        } finally {Files.deleteIfExists(target); fixture.cleanup();}
    }

    @Test void phaseControlRetriesMissingFileButRejectsInvalidJson() throws Exception {
        Path path=Files.createTempFile("phase-control", ".json");
        try {
            Files.delete(path);
            assertNull(readPhaseControl(path));
            Files.writeString(path,"{\"phase\":\"measurement\"}");
            assertEquals("measurement",readPhaseControl(path).path("phase").asString());
            Files.writeString(path,"invalid JSON");
            assertThrows(RuntimeException.class,()->readPhaseControl(path));
        } finally {Files.deleteIfExists(path);}
    }

    @Test void holdsServerUntilBoundedStopAndDrain() throws Exception {
        String directory=System.getProperty("load.runDir");
        if(directory==null) return;
        Path root=Path.of(directory); JsonNode config=config();
        fixture.configure(port,STUB);
        List<PostprocessLoadFixture.TripFixture> fixtures=new ArrayList<>();
        EventAppender appender=new EventAppender(root.resolve("worker-events.jsonl"));
        Logger logger=(Logger)LoggerFactory.getLogger("com.yeodam.yeodambe");
        appender.start();logger.addAppender(appender);
        measurements.beginRun(config.path("run_id").asString());
        observePipeline(measurements, STUB);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.path("total_budget_seconds").asLong());
        boolean drained=false;
        try {
            for(JsonNode count:config.path("fixture_counts")) {
                if(System.nanoTime()>=deadline) throw new IllegalStateException("Fixture bootstrap budget exhausted");
                var f=fixture.prepare(count.asInt(),0,null); fixtures.add(f);
                STUB.register(f.tripId(),new LocalAnalysisStub.RunSpec(1,1,LocalAnalysisStub.FaultMode.NONE,
                        config.path("ai_delay_ms").asLong(),0,0,false));
            }
            fixture.export(root.resolve("fixtures.json"),fixtures);
            write(root.resolve("ready.json"),Map.of("port",port,"fixtures",fixtures.size(),"context_path","/api"));
            String appliedPhase = null;
            while(!Files.exists(root.resolve("stop.json")) && System.nanoTime()<deadline) {
                JsonNode control=readPhaseControl(root.resolve("phase-control.json"));
                if (control != null) {
                    String phase=control.path("phase").asString();
                    if (!phase.equals(appliedPhase)) {
                        STUB.setReadyDelay(control.path("ai_ready_delay_ms").asLong());
                        appliedPhase=phase;
                        write(root.resolve("phase-applied.json"),Map.of("phase",phase));
                    }
                }
                write(root.resolve("idle.json"),idle());Thread.sleep(100);
            }
            long drainDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(config.path("drain_seconds").asLong()));
            // Allow any request still parsing multipart to enter the service.
            Thread.sleep(1000);
            while(!isIdle() && System.nanoTime()<drainDeadline) {write(root.resolve("idle.json"),idle());Thread.sleep(100);}
            drained=isIdle();
            Map<String,Object> validation=new LinkedHashMap<>();validation.put("idle",drained);
            if(drained) {
                validation.putAll(validate(root,fixtures,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(config.path("validation_seconds").asLong(config.path("drain_seconds").asLong())))));
                if(Boolean.TRUE.equals(validation.get("valid"))) fixture.cleanup();
                else validation.put("fixtures_preserved",true);
            } else validation.put("fixtures_preserved",true);
            write(root.resolve("validation.json"),validation);
            write(root.resolve("drain.json"),validation);
        } finally {
            measurements.endRun();measurements.onSample(sample -> {});STUB.onAnalysisReceived(receipt -> {});STUB.setReadyDelay(0);
            logger.detachAppender(appender);appender.stop();
            if(!drained) {
                write(root.resolve("drain.json"),Map.of("idle",false,"fixtures_preserved",true));
                // Keep Spring/worker alive for inspection; no unsafe shutdown/cleanup.
                long hold=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
                while(!Files.exists(root.resolve("release.json")) && System.nanoTime()<hold) Thread.sleep(250);
            }
        }
    }
    static void observePipeline(StageMeasurements measurements, LocalAnalysisStub stub) {
        measurements.onSample(sample -> {
            if (!Set.of("attachment_save","storage_retain").contains(sample.stage())) return;
            emitBoundary(Map.of("batch_id",sample.requestId(),"stage",sample.stage(),
                "start_ns",sample.startNanos(),"end_ns",sample.endNanos(),"outcome",sample.outcome(),
                "epoch_ms",System.currentTimeMillis(),"photo_count",0));
        });
        stub.onAnalysisReceived(receipt -> emitBoundary(Map.of(
            "batch_id",receipt.requestId(),"execution_id",receipt.executionId(),"trip_id",receipt.tripId(),
            "stage","ai_request_received","start_ns",receipt.receivedNanos(),"end_ns",receipt.receivedNanos(),
            "epoch_ms",System.currentTimeMillis(),"outcome","success","photo_count",0)));
    }
    private static void emitBoundary(Map<String,Object> event) {
        LoggerFactory.getLogger(DerivativeK6ServerTest.class).atInfo().addKeyValue("worker",event).log("테스트 경로 계측");
    }

    Map<String,Object> idle() {
        return Map.of("epoch_ms",System.currentTimeMillis(),"active_uploads",measurements.activeUploads(),
                "active_batches",(int)registry.get("yeodam.image.worker.active").gauge().value(),
                "queue_size",(int)registry.get("yeodam.image.worker.queue").gauge().value());
    }
    boolean isIdle() {var values=idle();return values.get("active_uploads").equals(0)&&values.get("active_batches").equals(0)&&values.get("queue_size").equals(0);}

    Map<String,Object> validate(Path root,List<PostprocessLoadFixture.TripFixture> fixtures,long deadline) throws Exception {
        Map<String,String> outcomes=new HashMap<>();Set<String> executions=new HashSet<>();
        for(String line:Files.readAllLines(root.resolve("worker-events.jsonl"))) {
            JsonNode event=JSON.readTree(line);
            if(event.path("stage").asString().equals("end"))outcomes.put(event.path("batch_id").asString(),event.path("outcome").asString());
            if(event.has("execution_id"))executions.add(event.path("execution_id").asString());
        }
        List<String> errors=new ArrayList<>();List<Map<String,Object>> checks=new ArrayList<>();Set<String> expected=new HashSet<>();
        Path checksDir=root.resolve("output-validation");Files.createDirectories(checksDir);
        for(var entry:STORED.entrySet()) {
            String request=entry.getKey();boolean success="success".equals(outcomes.get(request));
            List<Stored> originals=entry.getValue().stream().filter(s->s.kind().equals("original")).toList();
            for(Stored stored:entry.getValue()) {
                if(System.nanoTime()>=deadline)throw new IllegalStateException("Output validation budget exhausted; fixtures preserved");
                if(!success) {
                    try {storage.size(stored.key());errors.add("Failed/rejected batch object remains: "+stored.key());}
                    catch(RuntimeException expectedMissing) {
                        if(!(expectedMissing instanceof software.amazon.awssdk.services.s3.model.S3Exception s3) || s3.statusCode()!=404)errors.add("Cannot confirm object absence: "+stored.key());
                    }
                    continue;
                }
                expected.add(stored.key());
                Path copy=Files.createTempFile(checksDir,"output-","."+stored.kind());
                try(InputStream input=storage.open(stored.key())) {Files.copy(input,copy,StandardCopyOption.REPLACE_EXISTING);}
                try {
                    if(stored.kind().equals("original")) {
                        try(InputStream input=Files.newInputStream(copy)) {if(!stored.hash().equals(hash(input)))errors.add("Original hash mismatch: "+stored.key());}
                    }
                    String dimensions=tool("/usr/bin/identify","-format","%w %h",copy+"[0]");
                    String format=tool("/usr/bin/identify","-format","%m",copy+"[0]");
                    if(stored.kind().equals("preview") && !format.equals("WEBP"))errors.add("Preview format: "+stored.key());
                    if((stored.kind().equals("display")||stored.kind().equals("analyze")) && !format.equals("JPEG"))errors.add("JPEG format: "+stored.key());
                    String[] size=dimensions.trim().split(" "); int width=Integer.parseInt(size[0]),height=Integer.parseInt(size[1]);
                    JsonNode metadata=JSON.readTree(tool("/usr/bin/exiftool","-j","-n",copy.toString())).get(0);
                    if(stored.kind().equals("analyze")||stored.kind().equals("preview")) {
                        if(Math.max(width,height)>1024)errors.add("Oversize derivative: "+stored.key());
                        if(stored.kind().equals("analyze") && metadata.path("Orientation").asInt(1)!=1)errors.add("Analyze orientation: "+stored.key());
                    }
                    if(stored.kind().equals("preview")||stored.kind().equals("display")) {
                        if(metadata.has("Orientation")||metadata.has("DateTimeOriginal")||metadata.has("GPSLatitude"))errors.add("Unexpected EXIF: "+stored.key());
                    }
                    checks.add(Map.of("request_id",request,"photo_index",stored.photoIndex(),"kind",stored.kind(),"width",width,"height",height,"decode",true));
                } catch(Exception failure) {errors.add("Output verification failed: "+stored.key()+" "+failure.getClass().getSimpleName());}
                finally {Files.deleteIfExists(copy);}
            }
        }
        for(var check:checks) {
            if(check.get("kind").equals("display")) {
                var original=checks.stream().filter(c->c.get("request_id").equals(check.get("request_id")) && c.get("photo_index").equals(check.get("photo_index")) && c.get("kind").equals("original")).findFirst();
                if(original.isEmpty() || !original.get().get("width").equals(check.get("width")) || !original.get().get("height").equals(check.get("height")))errors.add("HEIC display dimensions differ");
            }
        }
        Set<String> referenced=new HashSet<>();
        for(var f:fixtures) {
            referenced.addAll(fixture.jdbc.query("select f.object_key,a.analyze_storage_key,a.preview_storage_key,a.display_storage_key from trip_attachments a join files f on f.file_id=a.file_id where a.trip_id=?",(rs,n)->{
                List<String> keys=new ArrayList<>();for(int i=1;i<=4;i++)if(rs.getString(i)!=null)keys.add(rs.getString(i));return keys;},f.tripId()).stream().flatMap(List::stream).toList());
        }
        List<String> orphans=new ArrayList<>();
        for(String execution:executions) {
            var pages=fixture.performanceS3.listObjectsV2Paginator(b->b.bucket("yeodam-performance").prefix("trip-uploads/"+execution+"/"));
            pages.contents().forEach(object->{if(!referenced.contains(object.key()))orphans.add(object.key());});
        }
        if(!orphans.isEmpty())errors.add("Orphan objects: "+orphans.size());
        if(!expected.equals(referenced))errors.add("DB/object references differ");
        List<String> tempResiduals;
        try(var paths=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            tempResiduals=paths.filter(p->p.getFileName().toString().startsWith("사진 작업 디렉터리")).map(Path::toString).toList();
        }
        if(!tempResiduals.isEmpty())errors.add("Temporary image files remain");
        return Map.of("valid",errors.isEmpty(),"errors",errors,"output_checks",checks,"orphan_objects",orphans,"temp_residuals",tempResiduals,
                "referenced_objects",referenced.size(),"validated_objects",expected.size());
    }
    static String hash(InputStream stream) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int read;
        while((read=stream.read(buffer))!=-1)digest.update(buffer,0,read);
        return HexFormat.of().formatHex(digest.digest());
    }
    static String tool(String... args) throws Exception {
        Process process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var reader=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return process.getInputStream().readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}});
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Output validation tool timeout");}
        if(process.exitValue()!=0)throw new IllegalStateException("Output validation tool failed");
        return new String(reader.get(1,TimeUnit.SECONDS),java.nio.charset.StandardCharsets.UTF_8);
    }
    static JsonNode readPhaseControl(Path path) throws IOException {
        try {return JSON.readTree(Files.readString(path));}
        catch (NoSuchFileException missingDuringReplacement) {return null;}
    }
    static void write(Path path,Object value) throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,JSON.writeValueAsString(value));
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static class EventAppender extends AppenderBase<ILoggingEvent> {
        final BufferedWriter writer;
        EventAppender(Path target) throws IOException {writer=Files.newBufferedWriter(target);}
        @Override protected synchronized void append(ILoggingEvent event) {
            if(event.getKeyValuePairs()==null)return;
            for(var pair:event.getKeyValuePairs())if(pair.key.equals("worker")) {
                try {writer.write(JSON.writeValueAsString(pair.value));writer.newLine();writer.flush();}
                catch(IOException failure){throw new UncheckedIOException(failure);}
            }
        }
        @Override public void stop() {super.stop();try{writer.close();}catch(IOException e){throw new UncheckedIOException(e);}}
    }
}
