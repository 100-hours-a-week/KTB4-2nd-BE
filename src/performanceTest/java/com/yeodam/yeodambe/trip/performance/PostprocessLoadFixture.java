package com.yeodam.yeodambe.trip.performance;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

final class PostprocessLoadFixture {
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean static StageMeasurements measurements() {return new StageMeasurements();}
        @Bean PostprocessLoadFixture fixture() {return new PostprocessLoadFixture();}
        @Bean SmartInitializingSingleton installSaveMeasurement(StageMeasurements m,TripAnalysisResultService results) {
            return ()->m.instrumentSave(results);
        }
        @Bean(destroyMethod="close") S3Client performanceS3() {
            return S3Client.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create("http://127.0.0.1:14566"))
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"))).build();
        }
    }
    record TripFixture(long userId,long tripId,String accessToken,String csrfContext,String csrfToken,int photoCount) {}
    @Autowired JdbcTemplate jdbc;
    @Autowired StageMeasurements measurements;
    private Duration requestTimeout=Duration.ofSeconds(120);
    void requestTimeout(Duration timeout){requestTimeout=timeout;}
    void awaitServerIdle(Duration timeout) throws InterruptedException {
        long deadline=System.nanoTime()+timeout.toNanos();while(measurements.activeUploads()>0 && System.nanoTime()<deadline)Thread.sleep(25);
        if(measurements.activeUploads()>0)throw new IllegalStateException("Server still processing; fixtures preserved");
    }
    @Autowired LoginSessionIssuer sessions;
    @Autowired AccessTokenIssuer tokens;
    @Autowired CsrfTokenStore csrf;
    @Autowired UserStatsService stats;
    @Autowired PlatformTransactionManager transactions;
    @Autowired S3Client performanceS3;
    @Autowired TripAttachmentStorageClient storage;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> users=new CopyOnWriteArrayList<>(),trips=new CopyOnWriteArrayList<>();
    private final List<String> contexts=new CopyOnWriteArrayList<>(),historyKeys=new CopyOnWriteArrayList<>();
    private String base; private LocalAnalysisStub stub;
    private byte[] photo;
    static LocalAnalysisStub newStub() {try{return new LocalAnalysisStub();}catch(IOException e){throw new UncheckedIOException(e);}}
    static void properties(DynamicPropertyRegistry r,LocalAnalysisStub stub) {
        r.add("spring.config.import",()->"");
        r.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:13307/yeodam?serverTimezone=Asia/Seoul&characterEncoding=utf8");
        r.add("spring.datasource.username",()->"yeodam");r.add("spring.datasource.password",()->"yeodam");
        r.add("attachment.s3.endpoint",()->"http://127.0.0.1:14566");r.add("attachment.s3.bucket",()->"yeodam-performance");
        r.add("attachment.s3.path-style",()->"true");
        r.add("ai.server.base-url",()->stub.aiBaseUrl().toString());r.add("ai.server.api-key",()->"test");
        r.add("ai.server.instance-id",()->"i-performance");r.add("kakao.local.base-url",()->stub.kakaoBaseUrl().toString());
        r.add("kakao.local.timeout",()->"3s");r.add("kakao.local.max-concurrency",()->"5");
    }
    void configure(int port,LocalAnalysisStub stub) {
        this.base="http://127.0.0.1:"+port+"/api";this.stub=stub;
        try {
            var targets=java.nio.file.Path.of("performance/targets/backend.json");
            java.nio.file.Files.createDirectories(targets.getParent());
            java.nio.file.Files.writeString(targets, "[{\"targets\":[\"host.docker.internal:"+port+"\"],\"labels\":{\"namespace\":\"performance\",\"application\":\"yeodam-be\"}}]");
        } catch(IOException e) {throw new UncheckedIOException(e);}

        try {
            if(photo==null) {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB),"jpg",out);photo=out.toByteArray();}
            performanceS3.headBucket(b->b.bucket("yeodam-performance"));
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    TripFixture prepare(int n,int h,Long sharedUser) throws Exception {
        if(n<1||n>200||h<0)throw new IllegalArgumentException("Invalid fixture size");
        long user=sharedUser==null?insert("insert into users(email,nickname) values (?,?)",UUID.randomUUID()+"@fixture.invalid","부하테스트"):sharedUser;
        if(sharedUser==null) {users.add(user);jdbc.update("insert into user_stats(user_id) values (?)",user);}
        var session=sessions.issue(user);String token=tokens.issue(user,session.sid());String context="perf-"+UUID.randomUUID(),csrfToken=UUID.randomUUID().toString();
        contexts.add(context);csrf.save(context,csrfToken);
        var provisional=new TripFixture(user,0,token,context,csrfToken,n);
        var response=http.send(auth(HttpRequest.newBuilder(URI.create(base+"/trips")),provisional,true,true)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "tripName",UUID.randomUUID().toString().substring(0,8),"startDate","2026-09-01","endDate","2026-09-02","regionCodes",List.of("50110"))))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());
        long trip=json.readTree(response.body()).path("data").path("tripId").asLong();assertTrue(trip>0,response.body());trips.add(trip);
        if(h>0)seedHistory(user,h);
        return new TripFixture(user,trip,token,context,csrfToken,n);
    }
    private void seedHistory(long user,int h) {
        long trip=insert("insert into trips(user_id,trip_name,start_date,end_date,processing_status) values (?,'이력','2026-09-01','2026-09-02','COMPLETED')",user);trips.add(trip);
        long place=insert("insert into trip_detail_places(trip_id,place_name,latitude,longitude,started_at,ended_at,order_number) values (?,'이력장소',33,126,'2026-09-01','2026-09-02',1)",trip);
        for(int i=0;i<h;i++) {
            String prefix="performance-history/"+UUID.randomUUID();List<String> keys=List.of(prefix+"/original",prefix+"/analyze",prefix+"/preview");
            for(String key:keys){historyKeys.add(key);performanceS3.putObject(b->b.bucket("yeodam-performance").key(key),RequestBody.fromBytes(photo));}
            long file=insert("insert into files(user_id,original_file_name,object_key,mime_type,upload_status) values (?,'fixture.jpg',?,'image/jpeg','UPLOADED')",user,keys.get(0));
            jdbc.update("insert into trip_attachments(trip_id,trip_place_id,file_id,region_origin,issue,classification_status,analyze_storage_key,preview_storage_key) values (?,?,?,'INFERRED','NONE','ACTIVE',?,?)",trip,place,file,keys.get(1),keys.get(2));
        }
        validateHistory();new TransactionTemplate(transactions).executeWithoutResult(s->stats.refreshFromActiveTrips(user));
    }
    List<String> historyKeys() {return List.copyOf(historyKeys);}
    void validateHistory() {for(String key:historyKeys)assertTrue(storage.size(key)>0);}
    void deleteObject(String key) {performanceS3.deleteObject(b->b.bucket("yeodam-performance").key(key));}
    private long insert(String sql,Object... args) {
        var holder=new GeneratedKeyHolder();jdbc.update(connection->{var ps=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    void uploadBeforeFinal(TripFixture f) throws Exception {
        int batches=(f.photoCount()+9)/10;for(int batch=1;batch<batches;batch++)assertEquals(204,uploadBatch(f,batch,false,"prepare-"+UUID.randomUUID(),true,true).statusCode());
    }
    HttpResponse<String> uploadFinal(TripFixture f,String requestId) throws Exception {return uploadBatch(f,(f.photoCount()+9)/10,true,requestId,true,true);}
    HttpResponse<String> uploadBatch(TripFixture f,int batch,boolean complete,String requestId,boolean access,boolean validCsrf) throws Exception {
        String boundary="perf"+UUID.randomUUID();List<byte[]> parts=new ArrayList<>();
        int count=Math.min(10,f.photoCount()-(batch-1)*10);
        for(int i=0;i<count;i++) {parts.add(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"attachments[]\"; filename=\"fixture.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));parts.add(photo);parts.add("\r\n".getBytes());}
        for(var entry:Map.of("batchNo",String.valueOf(batch),"totalAttachmentCount",String.valueOf(f.photoCount()),"complete",String.valueOf(complete)).entrySet())
            parts.add(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n").getBytes());
        parts.add(("--"+boundary+"--\r\n").getBytes());
        return http.send(auth(HttpRequest.newBuilder(URI.create(base+"/trips/"+f.tripId()+"/initial-attachments")),f,access,validCsrf)
            .timeout(requestTimeout).header("X-Request-ID",requestId).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder auth(HttpRequest.Builder builder,TripFixture f,boolean access,boolean validCsrf) {
        return builder.header("Cookie",(access?"accessToken="+f.accessToken()+"; ":"")+"CSRF_CONTEXT="+f.csrfContext())
            .header("X-CSRF-TOKEN",validCsrf?f.csrfToken():"invalid");
    }
    String poll(TripFixture f) throws Exception {
        var response=http.send(auth(HttpRequest.newBuilder(URI.create(base+"/trips/"+f.tripId()+"/processing-status")),f,true,true)
            .timeout(Duration.ofSeconds(10)).header("X-Request-ID","poll-"+UUID.randomUUID()).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());return json.readTree(response.body()).path("data").path("status").asString();
    }
    String databaseStatus(long trip) {return jdbc.queryForObject("select processing_status from trips where trip_id=?",String.class,trip);}
    int fallbackCount(long trip) {return jdbc.queryForObject("select count(*) from trip_detail_places where trip_id=? and place_name like '장소 %'",Integer.class,trip);}
    void assertCompleted(TripFixture f,int p) throws Exception {
        assertEquals("COMPLETED",databaseStatus(f.tripId()));
        assertEquals(p,jdbc.queryForObject("select count(*) from trip_detail_places where trip_id=?",Integer.class,f.tripId()));
        assertEquals(f.photoCount(),jdbc.queryForObject("select count(*) from trip_attachments where trip_id=? and classification_status='ACTIVE' and trip_place_id is not null",Integer.class,f.tripId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from trip_attachments a join trip_detail_places p on a.trip_place_id=p.trip_place_id where a.trip_id=? and p.trip_id<>a.trip_id",Integer.class,f.tripId()));
        var response=http.send(auth(HttpRequest.newBuilder(URI.create(base+"/trips/"+f.tripId())),f,true,true).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
    }
    void cleanup() {
        if(measurements.activeUploads()>0)throw new IllegalStateException("Cannot clean fixtures while server processes uploads");
        Set<String> keys=new HashSet<>(historyKeys);
        for(Long trip:trips) {
            keys.addAll(jdbc.query("select f.object_key,a.analyze_storage_key,a.preview_storage_key,a.display_storage_key from trip_attachments a join files f on f.file_id=a.file_id where a.trip_id=?",(rs,n)->{
                List<String> k=new ArrayList<>();for(int i=1;i<=4;i++)if(rs.getString(i)!=null)k.add(rs.getString(i));return k;},trip).stream().flatMap(List::stream).toList());
            stub.forget(trip);
        }
        for(String key:keys)deleteObject(key);
        for(Long trip:trips){jdbc.update("delete from trip_attachments where trip_id=?",trip);jdbc.update("delete from trip_detail_places where trip_id=?",trip);jdbc.update("delete from trip_regions where trip_id=?",trip);jdbc.update("delete from trips where trip_id=?",trip);}
        for(String context:contexts)csrf.delete(context);
        for(Long user:users){jdbc.update("delete from files where user_id=?",user);jdbc.update("delete from login_sessions where user_id=?",user);jdbc.update("delete from user_stats where user_id=?",user);jdbc.update("delete from users where user_id=?",user);}
        users.clear();trips.clear();contexts.clear();historyKeys.clear();
    }
}
